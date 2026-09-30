# dsh-mobile

[English](README.md) | **中文**

**用手机遥控家里那台电脑上运行的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 智能体。**

一个 Android 应用 + 一组 DSH 宿主插件，把一台跑着 DSH 的电脑变成 7×24 可远程遥控的智能体：与桌面端**同一份对话数据**、**同一套 Web 界面**，再加上手机真正需要的那几件事——远程唤醒、有活时才不睡的睡眠策略、智能体需要你时推送到手机、以及一块让家里没人能偷看你屏幕的防窥屏。

> **非官方项目。** 这是社区作品，与 DeepSeek 无隶属、无背书、无赞助关系。DSH 本身是 MIT 许可，这正是本项目得以存在的前提——见 [LICENSE](LICENSE)。

---

## 为什么需要它

DSH 的桌面端，剥开看就是**一个本机 HTTP 服务加一个浏览器界面**。这意味着手机可以和桌面端对话**同一个宿主进程**、看到**同一份会话文件**——没有同步层、没有冲突解决、没有复制。

但真拿来远程用，缺四样东西，本项目补上它们：

| 缺口 | dsh-mobile 补什么 |
|---|---|
| 宿主只绑 `127.0.0.1`，且 CLI 明确拒绝 `--host 0.0.0.0` | 隧道方案 + 一行 profile 补丁 |
| 没有任何机制阻止机器在干活中途睡着，或让它在空闲时保持睡眠 | **`sleep-guard`**：工作感知的睡眠抑制 |
| 没有任何办法从外面唤醒一台睡着的机器 | **唤醒栈**：RTC 定时自醒 + 局域网唤醒中继 |
| DSH 完全没有出站通知通道 | **`mobile-bridge`**：推送、防窥屏、唤醒触发 |

---

## 架构

```
┌──────────────────────────────────────────────┐
│  Android 应用（Kotlin + Compose）              │
│  · 原生外壳：设置、唤醒、生物识别锁            │
│  · WebView：对话界面                          │
│    （就是 DSH 自己的 Web 客户端）              │
└───────────────┬──────────────────────────────┘
                │ WireGuard 隧道 / frp
                ▼
┌──────────────────────────────────────────────┐
│  家里的电脑 —— 唯一的 DSH 宿主                 │
│                                               │
│  dsh --profile web --port 3080                │
│    ├─ HTTP /api/*           （RPC）           │
│    ├─ WS  /api/remote.mux   （流式）           │
│    └─ 静态界面                                │
│                                               │
│  plugins/sleep-guard      工作感知睡眠抑制      │
│  plugins/mobile-bridge    推送 + 防窥          │
│                                               │
│  ~/.dsh/sessions/<项目>/<会话ID>/              │
│      session.lock            ← 排他写锁        │
│      session.v4.jsonl.zstd   ← 对话数据        │
└──────────────────────────────────────────────┘
                ▲
                │ magic packet（仅限同一子网）
        wol-bridge（树莓派 / NAS / 路由器）
```

### 唯一的架构硬约束

DSH 的会话文件由一把**内核排他锁**守护（`flock`，无超时、无抢占）。**同一时刻只有一个宿主进程能写一个会话。**

所以手机和桌面端必须是**同一个宿主进程的两个客户端**，而不是两个 DSH 实例。这也正是"同步"是伪需求的原因：数据从头到尾只有一份。

---

## 组件

### `android/` —— 应用

原生 Kotlin + Jetpack Compose 外壳，包着一个 WebView。

**原则是：来自宿主的一切交给 WebView，宿主做不到的才用原生。**

对话界面加载的是**DSH 自己的 Web 客户端**。这是刻意的——转录、工具调用卡片、审批面板、输入框因此与桌面端完全一致，而且**DSH 升级时它们自动跟着走，没有任何需要维护的重实现**。

原生负责：宿主地址与设置、唤醒、生物识别锁、进程级重连。

> 原本计划里有一个**原生会话列表**，后来**撤回了**。原生渲染它意味着要重新实现 DSH 的 RPC 信封**和**一次性令牌换 Cookie 的流程，只为了画一个 web 客户端已经画对的界面——等于把认证路径实现两遍。加载后的客户端里的侧栏就是会话列表。

### `plugins/sleep-guard/` —— 跟着工作走的睡眠

大家真正想要的行为：

| 状态 | 行为 |
|---|---|
| 空闲，没有任务 | **睡。** 省电、安静。 |
| 有回合或后台任务在跑 | **保持唤醒。** 绝不在任务中途睡着。 |
| 有定时任务待触发 | **到点自醒**（RTC 唤醒）。 |
| 你想用它了 | **从手机唤醒。** |

DSH 已经发出了恰好需要的事件，插件因此很小：

```ts
const busy = () =>
  ctx.agents.list().some(a => a.status === 'running') ||
  collectJobs(ctx).some(j => j.status === 'running' || j.status === 'stopping')
```

三个容易写错、而这里都处理了的细节：

- **`status === 'idle'` 不等于"没活干"**：空闲只表示没有 *driver* 在调度，而后台 `bash` 任务会比启动它的那个回合活得更久。只看 agent 会得到一台"构建到一半就睡着"的机器。
- **`ctx.jobs.list()` 不带参数只返回 unowned 任务**：必须再对每个活跃 agent（含子 agent）按会话 id 查一遍，否则会漏掉**每一个普通的后台任务**。
- **`'stopping'` 仍然占用机器**：活还没干完。

`busy()` 翻真就 spawn 一个 `caffeinate -i`，翻假就 kill 掉。**用子进程而不是原生断言 API 是刻意的**：断言的寿命因此绑定在一个进程上，插件崩溃或卡死都不可能让机器**永久无法睡眠**。

### `plugins/mobile-bridge/` —— 推送、防窥、唤醒

- **推送** —— DSH 没有出站通知通道。这个插件订阅 `approval/request` 和回合结束，POST 到一个 webhook（默认 [ntfy](https://ntfy.sh)）。**没有它，无人值守的智能体在需要审批时会静默失败**——DSH 的审批策略无超时且 fail-closed。
- **防窥屏** —— 远程操作时锁屏。注意这比像素级远程桌面工具能做的方式**更强**：它们必须用遮罩假装黑屏，因为真锁屏会让它们自己瞎掉；而 DSH 通过 HTTP 驱动机器，**根本不在乎显示器**，所以可以直接锁屏。
- **唤醒触发** —— 手机 App 里对 `wol-bridge` 发起请求的按钮。

### `wol-bridge/` —— 唤醒一台睡着的机器

Wake-on-LAN 是**广播包，跨不了路由器**，所以从外面唤醒必须靠**同一子网里一台已经醒着的设备**。

这是一段纯标准库的 Python 服务，跑在树莓派、NAS 或局域网上任何常开的机器上，外加一个供手机经隧道访问的 HTTP 端点。

**如果你不想额外跑东西，先去路由器管理页看看有没有内置的网络唤醒功能——很多都有。**

---

## 快速开始

> 详细文档在 [`docs/`](docs/)。这里只是轮廓。

**1. 让宿主可达。** 跑一个独立的 DSH 宿主，前面放隧道：

```sh
dsh --profile web --no-open --port 3080
```

然后接 WireGuard/Tailscale，或 `frp` 转发到一台小 VPS。完整做法（包括 DSH 接受非 loopback `Host` 头之前必须打的信任围栏补丁）见 [`docs/setup-tunnel.md`](docs/setup-tunnel.md)。

**2. 安装插件**到你的 DSH profile，重启宿主。

**3. 配置唤醒栈。** 在接电源时开启 Wake-on-LAN，并且——**这条坑所有人**——**关掉随机化的 Wi-Fi MAC 地址**，否则你的 magic packet 会发给一个机器早已不再使用的地址：

```sh
networksetup -getMACAddress Wi-Fi   # 硬件 MAC
ifconfig en0 | grep ether           # 实际在用的 MAC
```

两者不一致就先关掉该网络的「私有 Wi-Fi 地址」。见 [`docs/setup-power.md`](docs/setup-power.md)。

**4. 装 App。** [`v0.1.0-pre`](https://github.com/SunWay0573/dsh-mobile/releases/tag/v0.1.0-pre) 有可直接侧载的 APK：

```sh
adb install app-debug.apk
```

也可以自己构建——见 [`android/`](android/README.md)。**发布签名密钥属于项目维护者的决定，而且是个秘密**，所以这个预发布版是 debug 签名的，并且在文档里明说了。

**验证这一切**用 CI 调用的同一个脚本：

```sh
scripts/verify.sh                          # 全部，含启动真实宿主
scripts/verify.sh --strict                 # CI 跑的；缺工具链算失败
scripts/verify.sh --without-integration   # 跳过最慢的那项
```

**一个全新的 `git clone` 用这一条命令就能全绿，无需任何额外配置。** 它会自己装插件依赖、构建、跑全部测试、构建 Android APK，并启动一个真实 DSH 宿主把两个插件装上。

最后那项不是装饰：**它是唯一能发现"插件安装干净、类型检查通过、每个单元测试都过、但就是从不运行"的检查**——这个问题在本项目里发生过三次。

---

## 状态

早期项目。设计已经稳定，建立在对手写 DSH 源码和一款商用远程桌面工具的实机审计之上。

| 组件 | 状态 | 测试 |
|---|---|---|
| 设计与架构 | ✅ [设计文档（英文）](docs/design.md)、[完整审计（中文）](docs/design.zh.md) | — |
| `plugins/sleep-guard` | ✅ 工作感知睡眠抑制 | 54 |
| `plugins/mobile-bridge` | ✅ 推送、防窥屏、唤醒触发 | 68 |
| `wol-bridge` | ✅ magic packet + HTTP 端点 | 73 |
| Android 应用 | ✅ 可构建；APK + 55 个单元测试已验证 | 55 |
| 发布 | ✅ [`v0.1.0-pre`](https://github.com/SunWay0573/dsh-mobile/releases/tag/v0.1.0-pre)，debug 签名 APK | — |
| CI | ⏸ 已写好，在 `ci-workflow` 分支；运行 [`scripts/enable-ci.sh`](scripts/enable-ci.sh) | — |

**共 250 个测试**，外加对真实 DSH 宿主的集成验证。

集成检查会做两件别的检查做不到的事：**确认插件真的被加载**，以及**确认 `sleep-guard` 真的在工作时持有睡眠断言**——夹具开一个后台任务，脚本从进程外用 `pmset -g assertions`（Linux 上是 `systemd-inhibit`）观察断言的出现与消失。**从进程外面观察，因为插件报告自己的状态证明不了任何事。**

那个"装上了但从不运行"的问题有三种不同的成因，且都通过了全部自动化检查。现在两种方式都在拦：源码层面由 [`scripts/check-plugins.mjs`](scripts/check-plugins.mjs) 检查，真实宿主层面由 [`scripts/verify-integration.sh`](scripts/verify-integration.sh) 检查。

### CI

工作流已经写好并在 `ci-workflow` 分支上，但 **GitHub 不允许任何 OAuth App 在没有 `workflow` token scope 的情况下创建或更新 `.github/workflows/`**——这道控制是为了防止被盗的 token 悄悄植入会窃取密钥的 CI。所以要把它装上去，需要**你在浏览器里授权一次**：

```sh
scripts/enable-ci.sh --check                  # 会精确报告缺什么
gh auth refresh -h github.com -s workflow     # 你在这步于浏览器中授权
scripts/enable-ci.sh                          # 装好并推送
```

五个 job：hygiene、plugins、wol-bridge、integration、android。

---

## 出问题的时候

[`docs/troubleshooting.md`](docs/troubleshooting.md)（英文）按**症状**组织，并且说明**如何确认**每个成因，而不只是"可能是"。几条值得在踩到之前就知道的：

- **插件装了，但什么也没发生。** 三种截然不同的原因，表现完全一样，而且全部能通过自动化检查。
- **403 与 401 的区别。** 403 是 `Host`/`Origin` 问题，和认证毫无关系。分清这两个能省下最多时间。
- **机器唤不醒。** 通常是随机化的 Wi-Fi MAC 地址——而你配的硬件 MAC 是错的那个。
- **定时任务没跑。** 机器睡着了它就不会跑——那些定时器是进程内的。

## 参与贡献

欢迎 issue 和 PR，见 [CONTRIBUTING.md](CONTRIBUTING.md)。

动手前有两件事值得知道：

- **绝不要提交凭据。** 隧道配置、`~/.dsh/.credentials.yaml`、ntfy topic、写死了地址的 WoL 脚本，都很容易泄漏。
- **如果你全局的 git 身份是公司邮箱，请为本仓库单独设置。** 一旦推送，邮箱就**永久写进公开历史**：
  ```sh
  git config user.email "<id>+<username>@users.noreply.github.com"
  ```

## 安全

本项目把一个能执行任意代码的智能体暴露出来。放到网络上之前请先读 [SECURITY.md](SECURITY.md)。简短版：**用隧道，绝不做公网端口转发；把隧道 ACL 限制到你自己的设备；把手机当作你电脑的钥匙。**

## 许可证

MIT —— 见 [LICENSE](LICENSE)。

DSH 本身是 MIT 许可（Copyright (c) 2026 DeepSeek）；本项目基于其公开插件 API 构建，不分发它的任何代码。细节与「非官方」声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
