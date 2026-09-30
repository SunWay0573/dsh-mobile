# 手机远程遥控家里的 DSH：7×24 智能体代理方案

> **目标**：把家里那台 Mac 变成 7×24 常驻的 DSH 宿主。手机（小米 / Android）随时连上去——界面接近桌面端、对话数据天然同一份、能发指令看流式输出、任务结束或需要确认时收到推送、**被控端屏幕防窥**、电脑睡着能唤醒。
>
> **v3 更新**：按你的要求把电源语义改成「**空闲可以睡，干活时不能睡，需要时能远程唤醒**」（§7.3 重写）。v2 的 UU远程 实机拆解见 §2，防窥方案见 §8。

---

## 1. 结论先行

**这件事不用写新 App。** DSH 桌面端本来就是一个"本机 HTTP 服务 + 浏览器 UI"（实测 `127.0.0.1:19387`，Token→Cookie 鉴权）。手机指向同一个服务，你得到的就是**同一份对话数据、同一套界面**。

工程量全在 DSH 目前**完全没有**的五件事上：

| # | 缺口 | 现状（源码/实测证据） | 补法 |
|---|---|---|---|
| 1 | **可达性** | 只绑 `127.0.0.1`；CLI 明确拒绝 `--host 0.0.0.0` | 隧道 + 改 profile patch |
| 2 | **7×24 常驻** | 宿主随 App 退出而死；全仓库 `caffeinate` 命中 0 次 | launchd 常驻 + 电源断言 |
| 3 | **手机界面** | 三栏最小宽 264+400+300；触摸/safe-area/虚拟键盘处理各 0 处 | PWA + 移动布局插件 |
| 4 | **推送 / 唤醒** | 无任何出站通知；审批无超时（无人应答=拒绝）；Apple Silicon 无法从关机态远程开机 | 通知插件 + ntfy；**工作感知睡眠抑制**（§7.3）+ **远程唤醒**（§9） |
| 5 | **防窥** | 完全没有 | **用系统锁屏**（见 §8，比 UU 的做法更强） |

**架构基石（不可动摇）**：会话文件 `session.lock` 是**排他写锁**（`flock`，无超时无抢占）。所以**同一时刻只有一个宿主进程能写一个会话**。

> 推论：手机和桌面要无缝接力同一个会话，它们**必须是同一个宿主进程的两个客户端**，而不是两个 DSH 实例。

### 1.1 来自 UU远程 的三条最重要结论

1. **远程唤醒的瓶颈不在软件，在网络拓扑。** UU 自己的文案写着：「局域网内没有辅助设备在线，无法转发唤醒指令辅助开机。」→ **WoL 包跨不了路由器，家里必须有一台常在线设备帮忙转发。** 但好消息是：你这台 M2 的 Wi-Fi 硬件实测支持 `Wake On Wireless`，**接收端条件是齐的**，只差发送端（§9.3）。
2. **防窥不要抄 UU。** UU 必须自绘黑屏遮罩，因为它是**像素级远程桌面**，真锁屏会让它自己瞎掉。DSH 的远程操作走 HTTP API，**和图形界面完全无关** → DSH 可以直接用系统锁屏，效果更强、零代码。
3. **别引入 root 守护进程。** UU 常驻一个 root LaunchDaemon + Mach service（永久提权攻击面）。我们要做的所有事**一个 root 进程都不需要**（只有 `pmset schedule` 那一条需要 sudo）。

---

## 2. 参考样本：UU远程 实机拆解

已装路径 `/Applications/UURemote.app`（NetEase，`com.netease.uuremote`）。

### 2.1 进程与常驻形态

实测四个进程：

```
510  1    root                      UURemoteDaemon -daemon        ← root，开机即起
789  1    Wei.Sun                   UURemote                      ← GUI，持有电源断言
1128 1    Wei.Sun                   UURemoteService -agent        ← 被控端服务
1248 1128 Wei.Sun  UURemoteServer                                   ← 实际工作进程
```

两个 launchd 配置（都在 `/Library`，需要管理员权限才能装）：

| 文件 | 关键字段 | 意义 |
|---|---|---|
| `/Library/LaunchDaemons/com.netease.uuremote.daemon.plist` | `RunAtLoad` + `KeepAlive` + `MachServices: com.uuremote.daemon` | **root 常驻**、崩溃自愈、提供提权 XPC |
| `/Library/LaunchAgents/com.netease.uuremote.agent.plist` | `LimitLoadToSessionType: [LoginWindow, Aqua]` + `MachServices` ×2 | **登录窗口就在跑** → 没人登录也能被控 |

> `LimitLoadToSessionType: LoginWindow` 是"没登录也能远程控制"的关键。**这个技巧我们直接借鉴**（见 §7.2）。

### 2.2 网络模型：出站长连接 + 云中继，不开放入站

`lsof` 实测：**没有任何监听端口**。唯一的连接是——

```
UURemoteServer  1248  TCP 192.168.10.3:65143 -> 42.186.187.203:443 (ESTABLISHED)
```

即：被控端主动向网易云中继（`42.186.187.203:443`）建立**长连接**，手机连同一个中继，中继负责配对。**不开入站端口 = 天然不需要公网 IP、不需要端口转发。**

我们没用它的中继，但**这个模型值得照搬思路**：让 Mac 主动出站连隧道，而不是等别人连进来。这正是 Tailscale/WireGuard 或 frp 的工作方式。

### 2.3 保活与唤醒（你最关心的部分）

**① 拒绝空闲睡眠——靠进程内电源断言**

`pmset -g assertions` 实测：

```
pid 789(UURemote): [0x000000300001818f] 59:41:20
    PreventUserIdleSystemSleep named: "UURemote Disable Idle System Sleep"
```

已经连续持有 **59 小时**。这就是 UU 的"不休眠"——**不是改系统设置，而是用 `IOPMAssertion` 盖住空闲睡眠**。

**② 电源策略：只动了 WoL，没动 sleep**

```
AC Power:     womp 1   tcpkeepalive 1   sleep 1（仍是 1！）
Battery:      womp 0   tcpkeepalive 1   sleep 1
```

注意 **它没有把 `sleep` 改成 0**。AC 下开了 `womp`（Wake for network access），配合 `tcpkeepalive` 让睡眠中的机器能被网络活动唤醒。**这是"不打扰用户系统设置"的取巧做法。**

**③ 远程开机：需要局域网辅助设备（它自己承认）**

二进制里有完整的一套实现：`WakeOnLanManager.swift`、`NowDeviceWakeOnLanButton`、`WakeOnLan.booting`（"正在开机"）、`/pmset`。中文文案是决定性证据：

| 文案 | 说明 |
|---|---|
| 「局域网内没有辅助设备在线，无法转发唤醒指令辅助开机。」 | ⚠️ **必须有 LAN 内常在线设备转发 magic packet** |
| 「如需远程开机，请确保该设备已完成远程开机配置，如有需要可查看教程。」 | 需要用户预先配置，不是开箱即用 |
| 「远程开机指令已发送，请等待设备开机。」 | 存在"正在开机"中间态 → 有不确定性 |

> **这条直接验证了本方案的判断**：商用软件也做不到"关机态远程开机"。Apple Silicon Mac **没有 WoL from S5**，也没有通电自启。所以 §9 的三级降级设计是唯一正确的姿势。

### 2.4 防窥：四档模式 + 一个受保护的遮罩窗口

产品文案完整拆出来：

| 模式 | 中文名 | 官方描述 | 实现线索 |
|---|---|---|---|
| `unusable` | 不启用 | 「被控端防窥模式已关闭」 | — |
| `blankScreen` | **黑屏防护** | 「在被控端显示屏上显示黑屏画面，遮挡屏幕内容。」 | `PrivacyScreenWindowLevelProtector.swift` |
| `closeScreen` | **关闭显示屏** | 「被控端显示屏已关闭，仅使用虚拟屏幕」 | 虚拟显示器 |
| `screensaver` | **隐私屏保** | 「设置隐私屏保」，可上传自定义图（≥1920×1080、≤2M） | 系统屏保 + 自定义图 |

技术要点（从符号表挖出来）：

- **`PrivacyScreenWindowLevelProtector`** —— 核心技巧：把遮罩窗口**钉在受保护的 window level**，防止被其它窗口盖住或被误关
- **`NoDisplaySleepAssertion`** —— 开防窥时**阻止显示器睡眠**，否则遮罩会消失
- `PS_NORMAL_PRIVACY` / `PS_ENHANCED_PRIVACY` 两档强度，`FF_PRIVATE_SCREEN_ENHANCED_PRIVACY` 灰度开关，`privacyScreenShortcut` 快捷键
- 「被控端系统版本较低，暂不支持隐私屏保」——屏保方案有系统版本门槛
- 鼠标提示：「注意该模式下开启隐私屏保，被控端无法完全隐藏鼠标。」

### 2.5 三条对我们有用的结论

1. **防窥要"钉住窗口 + 阻止显示器睡眠"** —— 这个组合拳是刚需，不管用哪种方案（§8.3 会说明为什么 DSH 可以更简单）
2. **常驻要 `RunAtLoad + KeepAlive` + `LoginWindow` 会话** —— 照抄
3. **永远不要引入 root 守护进程** —— UU 的 root daemon 和 Mach service 是永久提权攻击面（`rebootSystem`、`shutdownSystem` 这类操作都在里面）。我们要做的事（锁屏、息屏、电源断言）**普通用户权限就够**，这是相对 UU 的一个明显优势

### 2.6 ⚠️ 顺带提醒：你机器上已经有一堆同类工具在抢同一个活

| 工具 | 痕迹 | 影响 |
|---|---|---|
| **UU远程** | root LaunchDaemon + LoginWindow Agent | 已持有 `PreventUserIdleSystemSleep` 断言 |
| **Codex** | `~/Library/LaunchAgents/com.openai.codex.keep-awake.plist` → `caffeinate -i` | 已持有断言（实测 PID 1621，59 小时） |
| **Sleepless** | `Sleepless.app` + 两个 LaunchAgent | 又一个防睡工具 |
| **向日葵 / Oray** | `/Library/LaunchAgents/com.oray.remote.startup.plist` + `RemoteMac.app` | 第三套远程控制 |
| **RustDesk** | `/Applications/RustDesk.app` | 第四套 |
| **cc-connect** | `~/Library/LaunchAgents/com.cc-connect.service.plist` | 常驻服务 |
| **aTrust** | 公司 VPN | 可能与 Tailscale/WireGuard 冲突 |

**含义**：① 你现在"电脑会睡"其实是多个工具互相博弈的结果，先理清楚再叠新工具；② 同时跑 4 套远程控制软件本身是安全风险，建议只留 1–2 套；③ **Tailscale 与 aTrust 的网络扩展可能冲突**——这是 §6 里要先实测的项。

---

## 3. 现状盘点：DSH 给了你什么

### 3.1 已有的（可直接复用）

| 能力 | 说明 | 证据 |
|---|---|---|
| **同一套 Web UI** | 桌面 App 和 `dsh web` 跑同一个前端 | 桌面 0.2.0 以 `runProfile({profile:"desktop",args:["--no-open","--port","19387"]})` 启动，实测 `LISTEN 127.0.0.1:19387` |
| **会话持久化** | 每会话一目录，`session.v4.jsonl.zstd`，**append-only**（JSONL 套 Zstd 帧），带校验与断尾修复 | `packages/session/session-persistence-jsonl/src/{format,storage}.ts` |
| **服务端跑回合** | `session.prompt` 立即返回 `{accepted:true}`，**不等回合结束** → 关掉手机任务照跑 | `packages/api/session-controller/src/commands.ts:363-375` |
| **断线重连续传** | `session.follow` 首帧 `snapshot + cursor`，之后按 `seq` 补发，gap-free | `packages/api/session-controller/src/{index,history}.ts` |
| **多客户端同时看** | 同一会话可被多客户端 follow，返回**同一个 live Agent** | `packages/api/session-controller/src/agent.ts:187-188` |
| **流式输出** | `assistant-stream` 的 `start/chunk/end` | `packages/api/session-controller/src/types.ts:476-506` |
| **审批/提问走事件** | `approval/request`、`user-questions/request` waterfall 事件 | `packages/api/remotes/src/remote-events.ts:10-29` |
| **扩展点干净** | 所有 UI 都是槽位占用者，**不用 fork** 就能换 shell/composer/工具卡片 | `ctx.slots.register(...)` |
| **配置可控** | `ctx.settingsScope.bind(<schema>)` 走 Host settings 文档 | 例：`ui-chat` 的 `transcriptView`、`ui-theme` 的 `preference` |
| **目标自动续跑** | goal 是会话事件，driver 在 idle 时自动再发一轮 | `packages/goal/goal-round-driver/src/index.ts:259-294` |
| **定时任务** | 绝对时间 `at` + `every_seconds`（**最小 300 秒，无 cron**） | `docs/subsystems/schedule.md:67-98` |
| **Auto review** | **每次工具调用前模型审查**，放行→Full access，拒绝→转问用户 | 已装 `dsh-experimental-auto-review`，profile 已列出 |
| **Headless 模式** | `dsh --profile headless "任务"`，不占端口、跑完即退 | `packages/bundle/headless/README.md` |

### 3.2 缺失的

- ❌ 绑 `0.0.0.0`（CLI 主动拒绝）——只能靠 profile patch
- ❌ 任何**出站**通知（`packages/webhook` 是**入站**）
- ❌ 审批超时/排队（无人应答直接 fail-closed 拒绝）
- ❌ Job/Schedule 跨宿主重启的持久性（Job 全在内存）
- ❌ 睡眠/唤醒处理、keep-awake（全仓库 `caffeinate` 命中 **0** 次）
- ❌ 移动端适配、防窥

### 3.3 ⚠️ 版本落差

源码检出 `~/Work/deepseek-harness` 是 **0.1.5-rc.2**，已安装运行的是 **0.2.0-rc.2**。实质差异：0.1.5 的 desktop 组合**关掉了** webserver；0.2.0 改成跑 web profile + 桌面补丁 + 硬编码 19387；`experimental-auto-review`、`DesktopTray` 等只有 0.2.0 有。

**动手前先更新检出**，否则补丁对不上。

---

## 4. 目标与约束

### 4.1 目标

1. 手机看/继续桌面端已有会话（数据同步）
2. 手机新建会话、发指令、看流式输出
3. **电源语义**（v3 明确）：
   - 空闲（无任务在跑）→ **允许睡眠**
   - 工作中（有 agent/job 在跑）→ **禁止睡眠**
   - 需要时 → **能从手机远程唤醒**
   - 有定时任务 → **到点自醒**
4. 任务完成或需确认时手机收到推送
5. 定时 / 无人值守任务
6. **被控端防窥**（本地看不到屏幕内容、无法操作）
7. 7×24 远程遥控的智能体代理

### 4.2 硬约束

| 约束 | 影响 |
|---|---|
| `session.lock` 排他写锁、无超时无抢占 | **单宿主架构** |
| 审批 fail-closed、无超时 | 无人值守必须设 `never` 或开 Auto review |
| goal/schedule 依赖**进程内有 live agent** | 宿主必须一直在跑；goal 在 resume 后会被自动 disarm |
| **Apple Silicon 无法从关机态远程开机** | "唤醒"上限 = 从睡眠唤醒；且**空闲睡眠是期望行为**，正解是"工作感知抑制 + 按需唤醒"（§7.3 / §9） |
| 这是**公司电脑**（Workspace ONE / aTrust / 深信服） | MDM 可能拦网络扩展、锁电源策略；合规需自评 |

---

## 5. 总体架构

```
┌──────────────────────────────────────────────┐
│  小米手机 · Chrome / PWA                       │
│  · 加桌面快捷方式，全屏独立窗口                  │
│  · 移动版布局（单栏 + 抽屉）                     │
│  · 后台被杀 → 重连（500ms→10s 退避）             │
│  · 防窥开关 / 唤醒按钮 / ntfy 推送直达           │
└──────────────┬───────────────────────────────┘
               │ ① WireGuard 隧道 / frp — 端到端加密
               ▼
┌──────────────────────────────────────────────┐
│  家里的 Mac（唯一宿主，7×24）                   │
│                                               │
│  launchd LaunchAgent（KeepAlive + RunAtLoad）  │◄─ 崩溃自愈
│   └─ dsh --profile web --port 3080            │
│        ├─ HTTP /api/*          （RPC）         │
│        ├─ WS  /api/remote.mux  （流式多路复用） │
│        └─ 静态 UI（= 桌面端同一套前端）          │
│                                               │
│  ~/.dsh/sessions/<项目>/<会话ID>/              │
│      session.lock           ← 排他写锁          │
│      session.v4.jsonl.zstd  ← 对话数据          │
│                                               │
│  ② 保活：工作感知睡眠抑制（有活才断言，见 §7.3）│
│  ③ 防窥：系统锁屏（比 UU 更强，见 §8）          │
│  ④ 唤醒：空闲照睡 + WoL / RTC 定时自醒（见 §9）  │
│  ⑤ 推送：notify 插件 ─► ntfy ─► 手机            │
└──────────────────────────────────────────────┘
```

**"数据同步"是伪需求**：手机和桌面看的是**同一份会话文件**。不存在同步、不存在冲突。唯一要守的纪律是**只有一个宿主**。

---

## 6. 网络可达性

### 6.1 三个候选

| 方案 | 适合你吗 | 优点 | 缺点 |
|---|---|---|---|
| **A. Tailscale / 自建 Headscale** ⭐ | 首选，**大陆需实测** | 零公网 IP、端到端加密、ACL 精确到设备 | 控制面在大陆常被墙；**与 aTrust 可能冲突**（见 §2.6） |
| **B. frp + 自建云服务器** ⭐ | **大陆最稳的备选** | 完全自控、国内 VPS 便宜、可套 TLS | 要买 VPS、有公网暴露面需加固 |
| **C. 公网 IP + 端口转发** | 最省事但**最危险** | 延迟最低 | 一个能执行代码的 Agent 直接挂公网；多数宽带已 CGNAT |

> **建议**：先花 30 分钟测 A；不通就上 B（frp + 国内轻量 VPS + TLS）。
> **不要**把 3080 直接端口转发到公网。

### 6.2 无论选哪个，DSH 侧都要改

DSH 有一道 **Host/Origin 信任围栏**（`packages/client/connection/src/api-request-trust.ts:91-117`），`/api` 和 WebSocket 升级都过它：

- `Host` 必须是 loopback **或**在 `trustedHosts` 白名单
- 若带 `Origin`，必须**恰好等于** Host 的 authority
- `Sec-Fetch-Site: cross-site` → 403

所以隧道/反代**必须保留原始 `Host`**。而 `trustedHosts` **不在** webserver 行上，在 `connection` 行上。

**补丁 A：走隧道/反代（推荐，服务仍只绑 loopback）**

`~/.dsh/profiles/web/cordis.patch.yml`：

```yaml
- id: connection
  name: '@deepseek-ai/dsh-client-connection'
  inject: [webRuntime]
  config:
    trustedHosts: !!js ['dsh.example.com', ...ctx.webRuntime.trustedHosts]
```

**补丁 B：让 DSH 直接绑组网地址**

```yaml
- id: webserver
  name: '@deepseek-ai/dsh-host-webserver'
  inject: [webStartup]
  config:
    host: '0.0.0.0'
    port: !!js ctx.webStartup.port ?? 3080
    compression: gzip
    compressionLevel: 1
    compressionThresholdBytes: 1024
```

> 绑 `0.0.0.0` 时 DSH 会**自动把本机所有非 internal IPv4 加进 `trustedHosts`**（`packages/bundle/web-app/src/index.ts:125-132`）——Tailscale 的 `100.x.y.z` 会被自动采样。
> ⚠️ 副作用：同时暴露到家庭局域网。配合 macOS 防火墙只放行组网网段。

### 6.3 首次登录

鉴权是**一次性启动令牌换 Cookie**：`http://<地址>:<端口>/?token=<令牌>` → 303 → `HttpOnly; SameSite=Strict` 签名 Cookie（默认 30 天）。令牌每次进程启动重新生成。

- 宿主用 `--no-open` 启动，把打印的 URL 落到固定日志，重启后你自己复制一次
- ⚠️ Cookie 是设备信任：**手机丢了 = 电脑被控**（见 §13）

### 6.4 Tailscale 特有坑

`tailscale serve` 会终止 TLS 并可能**改写 `Host`**（上游新增 `X-Forwarded-Host/Proto`）。一旦 Host 被改成 `127.0.0.1:3080`，`Origin` 就对不上 → 403。**上线前必须用 `curl -v` 抓一次头验证**；不行就用补丁 B 走裸 tailnet IP。

---

## 7. 常驻与 7×24 保活

### 7.1 用独立 `dsh web`，而不是依赖桌面 App

桌面 App 的 19387 随 App 一起死（0.2.0 的退出框自己写着 *"Scheduled tasks will not run while the app is closed."*），且依赖图形登录会话。

**代价**：桌面 App 不能再开着写同一个会话。建议**以 `dsh web` 为准，桌面 App 作为可选本地查看端**。

> 若 CLI 不可用：桌面 App 内有命令链接工具（`Contents/Resources/runtime/cli/`）；否则 `npm i -g @deepseek-ai/dsh`。**Phase 0 先验证这条。**

### 7.2 launchd：照抄 UU 的常驻姿势

UU 的做法值得直接借鉴（`RunAtLoad + KeepAlive`，且 Agent 声明 `LimitLoadToSessionType: [LoginWindow, Aqua]`，**登录窗口就在跑**）。

`~/Library/LaunchAgents/com.user.dsh-web.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.user.dsh-web</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/local/bin/dsh</string>
    <string>--profile</string><string>web</string>
    <string>--no-open</string>
    <string>--port</string><string>3080</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>LimitLoadToSessionType</key>
  <array><string>LoginWindow</string><string>Aqua</string></array>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>/Users/你/Library/Logs/dsh-web.log</string>
  <key>StandardErrorPath</key><string>/Users/你/Library/Logs/dsh-web.err</string>
</dict></plist>
```

```sh
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.user.dsh-web.plist
launchctl print gui/$(id -u)/com.user.dsh-web | head -20
```

> 去掉 `LimitLoadToSessionType` 就是纯 Aqua（登录后才跑）。加上它，**登录窗口阶段也能跑**——这就是 UU"没登录也能被控"的原理。

### 7.3 电源：工作感知的睡眠抑制（v3 修正）

> **修正说明**：v2 建议常驻 `caffeinate`（永不休眠）。你已明确"**空闲时允许睡眠，工作时不能睡**"——这其实更优雅，也更省电更安全（风扇不转、没人在家时机器是冷的）。以下是修正后的设计。

**目标态**：`空闲 ⇄ 睡眠`，`有活 ⇄ 唤醒`。三条规则：

| 状态 | 行为 | 实现 |
|---|---|---|
| 空闲（无 agent 在跑） | **允许睡眠** | 不持有任何断言（= macOS 默认，`sleep 1` 保持不变） |
| 工作中（有 agent/job 在跑） | **禁止睡眠** | 持有 `PreventUserIdleSystemSleep` 断言 |
| 有定时任务待触发 | **到点自醒** | `pmset schedule wake`（见 §9.3 W1） |

#### 7.3.1 实现：一个约 80 行的 host 插件 `dsh-sleep-guard`

**钩子已经现成**（源码核实）：

| 用途 | API | 位置 |
|---|---|---|
| 状态翻转 `idle ⇄ running` | `ctx.on('agent/status', ({ agent, status }) => …)` | `packages/core/agent/src/runtime-types.ts:277`（`AgentStatus = 'idle' \| 'running'`，`:109`） |
| agent 生命周期 | `ctx.on('agent/created' / 'agent/disposed', …)` | 同文件 `:265` 附近 |
| 枚举活跃 agent | `ctx.agents.roots()` | `packages/schedule/schedule/src/index.ts:52` 有现成用法 |
| 后台 job | `ctx.jobs.list()` → `JobSnapshot.status`；`ctx.jobs.onJobsChanged()` | `packages/jobs/jobs/src/index.ts:90,167`、`types.ts:97,113` |

**判定逻辑**：

```ts
const busy = (): boolean =>
  ctx.agents.roots().some(a => a.status === 'running') ||
  ctx.jobs.list().some(j => j.status === 'running')   // 跑着的 bash / subagent
```

**执行逻辑**：

```
busy: false → true    spawn('caffeinate', ['-i'])   并记住 child
busy: true  → false   child.kill('SIGTERM')
插件 dispose          务必 kill（否则断言泄漏，机器再也不睡）
```

**为什么用 `caffeinate` 子进程而不是原生断言 API**：

- 零原生依赖、零编译
- **断言的生命周期与进程强绑定**——子进程死了断言必然释放。这消除了"状态事件丢一次，机器就永远不睡"这类最难查的 bug
- 这也正是 UU 做不到的：它由常驻进程持有常驻断言，没法做到"只在干活时断言"

**必须处理的边界情况**：

1. **`status === 'idle'` 不等于"没活干"**：后台 job（bash/subagent）可能仍在跑 → `agent/status` 和 `ctx.jobs` 要一起看
2. **兜底超时**：设一个最大持续时间（如 6 小时）强制释放，防止上游事件异常导致永不释放
3. **插件卸载路径**：在 `ctx.effect()` 的 disposer 里 kill，别只依赖 GC

#### 7.3.2 电源策略：只开 WoL，不动 `sleep`

```sh
# WoL 接收端（实测本机硬件已支持，见 §9.1）
sudo pmset -c womp 1              # AC 下 Wake for network access（当前已是 1）
sudo pmset -a tcpkeepalive 1      # 当前已是 1
sudo pmset -a autorestart 1       # 断电恢复后自动开机
sudo pmset -c displaysleep 10     # 屏幕照常睡

# sleep 保持 1 —— 不要改成 0！这正是你要的"空闲可以睡"
pmset -g custom | grep -E "sleep|womp"   # 复核
```

> **和 UU 的关键区别**：UU 是"常驻进程持常驻断言 → 永远不睡"；我们是"**按需断言 → 有活才不睡**"。这正是你要的语义。
> ⚠️ 顺带：你已有的 `com.openai.codex.keep-awake.plist`（`caffeinate -i`，实测 PID 1621 已持断言 59 小时）是**常驻**的，和这个目标直接冲突——建议改成按需，或直接停掉。

### 7.4 三个必须提前知道的坑

1. **MacBook 合盖必睡**。你说会保持插电、开盖——这就解决了。但要知道：**蛤壳模式**（合盖）需要外接显示器 + 电源 + 键鼠，`caffeinate` 拦不住合盖。你的是 `Mac14,7`（MacBook Pro M2）。
2. **FileVault 与自动登录互斥**。开了 FileVault，重启后卡登录界面，LaunchAgent 不跑，远程失联。要么自动登录（安全性下降），要么接受"重启后需人到现场"。
3. **MDM / 公司策略**。这台机器装了 Workspace ONE、aTrust 等。MDM 可能强制屏幕锁定、休眠策略、拦网络扩展（**Tailscale 需要网络扩展，很可能和 aTrust 冲突**）。务必先确认你有管理员权限，并在公司合规范围内评估。

---

## 8. 防窥设计（新增，且是本方案相对 UU 的优势点）

### 8.1 核心洞察：DSH 不需要模仿 UU

UU 为什么要费劲自绘黑屏遮罩（`PrivacyScreenWindowLevelProtector`）？因为**它是像素级远程桌面**——它要把被控端的画面传回手机，一旦真锁屏，它自己就没画面可传了。所以它只能"盖一层黑布"，而**这层布是脆弱的**：本地敲一下键盘可能就掀开（它自己的文案也承认「无法完全隐藏鼠标」）。

**DSH 完全不同**：DSH 的远程操作走 **HTTP API + WebSocket**，跑在宿主进程里，**和被控端的图形界面、显示器、锁屏状态完全无关**。

> 所以 DSH 可以用**系统锁屏**做防窥——这不是妥协，这是**升级**。

| | UU 的遮罩 | DSH 的系统锁屏 |
|---|---|---|
| 本地能看到内容 | 看不到（遮罩覆盖） | 看不到 |
| 本地能操作 | **可能能**（遮罩可被绕过） | **不能**，必须输密码 |
| 远程方受影响 | 会瞎（所以它才不敢用） | **完全不受影响** |
| 需要额外代码 | 需要（自绘窗口 + level 保护 + 屏保） | **零代码** |
| 需要 root | 不需要，但整个 daemon 是 root | 不需要 |

### 8.2 三级实现（推荐 L2）

| 级别 | 命令 | 效果 | 缺点 |
|---|---|---|---|
| **L1 息屏** | `pmset displaysleepnow` | 显示器立刻黑，机器照常跑 | 本地动一下鼠标/敲键就亮 |
| **L2 锁屏** ⭐ | `CGSession -suspend`（见下） | **真锁屏，本地必须输密码** | 需要用户会话存在 |
| **L3 自绘遮罩** | 抄 UU：`NSPanel` + `CGShieldingWindowLevel()` + `canJoinAllSpaces`/`fullScreenAuxiliary` + `kIOPMAssertionTypeNoDisplaySleep` 断言钉住 + `CGEventTap` 吞本地键鼠 | 不锁屏也能挡内容 | 要写代码；**安全性弱于锁屏** |

L2 的具体命令：

```sh
# 锁屏（macOS 内置，普通用户权限即可）
"/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession" -suspend

# 或者更现代的等价做法
osascript -e 'tell application "System Events" to keystroke "q" using {control down, command down}'
```

> ⚠️ 注意：**别用** `pmset displaysleepnow` 当作"防窥"——它只关屏幕，本地一动就恢复，而且**锁屏和息屏是两件事**。要安全就用 L2。

### 8.3 触发时机设计

做成 DSH 侧的一个小 host 插件 + 一个 macOS 辅助命令，手机端一键开关：

| 触发 | 行为 |
|---|---|
| 手机首次连接成功后 | 自动执行 L2 锁屏 |
| 手机点"临时解锁" | 本地输入密码解锁；N 分钟无操作后自动重新锁屏 |
| 手机断开超过 N 分钟 | 保持锁屏（默认最安全） |
| 你在家时 | 手机端点"关闭防窥"，或本地解锁即视为退出防窥 |

**实现载体**：复用 §12 的通知插件框架，多注册一个 `screen.curtain` 命令即可。整个能力大约 **50 行代码 + 一个 shell 调用**。

### 8.4 进阶：连 UU 的"黑屏但看得见画面"也可以做

如果你希望本地看到的是"正在被远程操作"的提示而不是纯黑（比如想让家人知道机器在用），再上 L3：`NSPanel` 全屏黑 + 居中文字。**但默认不需要，L2 更强。**

---

## 9. 唤醒设计

### 9.1 好消息：你这台机器的硬件条件是齐的

实测三项，全过：

| 检查 | 结果 | 命令 |
|---|---|---|
| Wi-Fi 硬件支持网络唤醒 | ✅ **`Wake On Wireless: Supported`** | `system_profiler SPAirPortDataType` |
| AC 下已开启网络唤醒 | ✅ `womp 1` | `pmset -g custom` |
| 机器**已经在被定时唤醒** | ✅ 有两个 Apple 自己排的 wake 事件（01:51 / 07:52） | `pmset -g sched` |

第三项特别有价值：它证明**"定时唤醒"这套机制在你这台 M2 上是真实工作的**，不是理论——Apple 自己就在用。

### 9.2 ⚠️ 先修一个坑：私有 Wi-Fi 地址会毁掉 WoL

实测发现硬件 MAC 和实际使用的 MAC **不一致**：

```
networksetup -getMACAddress Wi-Fi   →  a4:cf:99:5f:3e:af   （硬件 MAC）
ifconfig en0 | grep ether           →  7e:76:5e:29:e4:e1   （实际在用）
```

`7e:` 前缀 = 本地管理地址 = **macOS「私有 Wi-Fi 地址」随机化后的 MAC**。

**后果**：WoL magic packet 必须发给机器**当前实际使用**的那个 MAC。私有地址轮换后，你按网上教程填的硬件 MAC 就会失效——这是"配置看起来全对却唤不醒"的最常见原因。

**修复**：系统设置 → Wi-Fi → 当前网络 → 详情 → **关闭「私有 Wi-Fi 地址」**（或设为"固定"），重连 Wi-Fi 后以 `ifconfig en0` 的值为准重新记录 MAC。

### 9.3 三条唤醒路径（按可靠性排序）

**W1. 自我唤醒 —— 最可靠，零外部依赖** ⭐

```sh
# 让机器在指定时刻自己醒来（需要 sudo）
sudo pmset schedule wake "10/01/26 09:00:00"
pmset -g sched                  # 查看已排定的事件
sudo pmset schedule cancelall   # 清空
```

用途：

- **定时任务到点自醒** —— 完美解决"空闲睡眠"与"定时任务"的冲突：schedule 插件在 idle 期间的 `setTimeout` 是睡不动的，必须靠 RTC 叫醒
- **预约唤醒** —— 手机上说"20 分钟后我要用"，宿主先排一个 wake 事件

局限：需要在睡眠**之前**就把事件排好。所以 `dsh-sleep-guard` 插件应该在"即将空闲"时，读取该会话的 schedule 记录并排定下一次 wake。

**W2. 局域网辅助设备发 magic packet —— 通用远程唤醒**

物理限制：**WoL 是广播包，跨不了路由器**，必须在**同一个子网**内发（[TidBITS 论坛](https://talk.tidbits.com/t/wake-on-lan-with-apple-silicon/33183/9) 说得很直接："Wake-On-LAN packets do not easily traverse routers and firewalls"）。所以远程唤醒必然需要家里有一台常在线设备。

你已具备**接收端**全部条件（§9.1）。**发送端**三选一：

| 方案 | 说明 |
|---|---|
| **路由器自带网络唤醒** | 不少小米/华硕/TP-Link 路由器有这个功能，部分支持从 App/外网触发。**先查你的 `192.168.10.1` 管理页** |
| **一台常在线设备 + 脚本** ⭐ | 树莓派 / NAS / 旧手机 / 另一台常开的电脑，与 Mac 同网段，跑一个 5 行 Python 脚本 |
| **UU远程的"远程开机"** | 你已装，但需用户预先配置，且它自己的文案承认要"局域网内辅助设备转发" |

这正是 [Tailscale 官方博客](https://tailscale.com/blog/wake-on-lan-tailscale-upsnap)和 [tailscale-wakeonlan](https://github.com/andygrundman/tailscale-wakeonlan) 采用的模型：**Tailscale 负责让你从外网碰到那台辅助设备，辅助设备负责在局域网内发包**。Tailscale 自己**不能**唤醒睡眠中的机器（连接已经断了）。

发送脚本（放在辅助设备上）：

```python
#!/usr/bin/env python3
import socket, sys
mac  = sys.argv[1]                                  # 例如 7e:76:5e:29:e4:e1
addr = bytes.fromhex(mac.replace(':', '').replace('-', ''))
pkt  = b'\xff' * 6 + addr * 16
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.sendto(pkt, ('192.168.10.255', 9))                # 网段广播地址
```

**W3. 路由器端口转发 / DDNS** —— 不推荐：多数宽带已 CGNAT，且徒增暴露面。

### 9.4 手机侧体验设计

给"连接失败"专门做一个**唤醒引导页**（这正是 UU 用「正在开机」中间态解决的那个问题）：

1. 检测连不上 → 显示"电脑在睡觉，正在唤醒…"
2. 调用辅助设备的 WoL 接口（或路由器 API）
3. 每 3 秒轮询宿主端口，最多 60 秒
4. 连上 → 自动跳到会话列表
5. 超时 → 提示"唤醒失败"+ 排查清单（MAC 是否正确、私有 Wi-Fi 地址是否已关闭）

### 9.5 另一层含义：唤醒会话

如果你的"唤醒"是指把 DSH 叫起来干活：

- 已有会话：手机 `session.follow` 重新接入 → 直接接着发指令（服务端回合本来就在跑）
- 冷会话 continue：resume 时会**自动追加 synthetic closers** 处理被打断的回合
- ⚠️ **goal 在 resume/fork 后会被自动 disarm**（设计如此），需要重新确认
- 定时任务：`at` 绝对时间或 `every_seconds`（**≥300 秒，无 cron**）；**机器睡着时它不会触发**，要靠 W1 排 wake 事件

### 9.6 三条规则合起来得到的系统

| 场景 | 行为 |
|---|---|
| 没人在用 | **睡觉**（省电、安静、更安全） |
| 你有任务在跑 | **保持唤醒**（不被睡眠打断） |
| 有定时任务 | **到点自醒**（RTC 唤醒，零网络依赖） |
| 你想用了 | **手机点一下唤醒**（WoL magic packet） |

这比"永不休眠"更省电，也比"让它一直睡"更可靠。

---

## 10. 手机端界面设计

### 10.1 现状：手机上直接用会很难受

| 项 | 现状 |
|---|---|
| 布局断点 | 只有**一个** JS 断点 `SIDEBAR_AUTO_COLLAPSE = 1024`（`ui-layout/src/client/columns.ts:23`） |
| 栏最小宽 | 中 400 / 左 264 / 右 300 → 合计 964，**塞不进任何手机** |
| 触摸事件 | 全仓库 `onTouchStart/Move/End` = **0 个** |
| 安全区 | `env(safe-area-inset-*)`、`viewport-fit=cover`、`100dvh`、`visualViewport` = **0 处** |
| 响应式 | 0 个 `useMediaQuery`；45 个 `@media` 里 36 个是 `prefers-reduced-motion` |
| 已有基础 | 已有 `@media (pointer: coarse)` 和 44px 触摸目标（附件/交付物） |

**好消息**：`< 1024px` 时侧栏自动折叠，所以 **Phase 1 零改动就能用**。

### 10.2 Phase 1：零改动

手机 Chrome 打开隧道地址 → 菜单 →「添加到主屏幕」。全屏、无地址栏、图标独立，体验已接近 App。

### 10.3 Phase 2：`ui-mobile` 插件

DSH 的 UI 是**槽位驱动**的，跨插件 import 是构建错误（`tsdown.client.ts:489-499`），所以移动化**不需要 fork**，新增 `packages/client/ui-mobile/` 即可。

1. **外壳**：窄屏（`< 768px`）三栏改**单栏 + 抽屉**：顶栏（标题 + ☰ 会话列表 + ⋯ 右栏）、底部 sticky composer
2. **视口**：`index.html` 加 `viewport-fit=cover`；高度用 `100dvh`
3. **虚拟键盘**：监听 `window.visualViewport` 的 `resize`/`scroll` 调整 composer 偏移（安卓 Chrome 不处理会遮挡输入框）
4. **触摸**：hover 才出现的操作改长按/点击；触摸目标统一 ≥ 44px
5. **PWA**：`manifest.webmanifest` + 图标 + `display: standalone` + 极简 SW（只为可安装和推送，**不要**缓存 `/api`）
6. **会话列表页**：卡片式，带"运行中 / 需要确认"角标——手机端最高频入口

**工程细节**：新插件需 `dsh.client.platform === 'web'`、导出 `./client`、产出 `lib/client.js`，且 profile roster 里有一行——**HMR 只覆盖已 roster 的条目，不会新增 roster 行**。改 `apps/web` 外壳要 `pnpm run build:web` 并刷新。移动端偏好走 `ctx.settingsScope.bind(<schema>)`，**不要**走 `cordis.patch.yml`（客户端插件收不到）。

---

## 11. 对话数据"同步"：其实不需要同步

- 会话落在 `~/.dsh/sessions/<项目转义>/<会话ID>/session.v4.jsonl.zstd`，**append-only**，同一台机器、同一个宿主
- 手机和桌面是**同一份数据的两个视图**；手机发的指令和桌面看到的气泡是同一个 `seq` 流
- 多客户端同时 follow 同一会话**被支持**（`resolveAgent` 返回同一个 live Agent）
- 断线重连由 `snapshot + cursor` 保证 gap-free，客户端自带 500ms→10s 退避

**唯一纪律：只有一个宿主进程。** 违反的表现是 `SessionAlreadyOwnedError`，不是数据损坏——append-only + 租约设计不允许静默写坏。

---

## 12. 推送通知（必须自建）

### 12.1 为什么必须有

- 审批**没有超时、没有排队**：无人应答 → 直接 fail-closed 拒绝
- `goal` 和 `Schedule` 都依赖**进程内有 live agent**
- 手机 WebSocket 在后台会被 MIUI 杀，不能指望它挂着流

**推送是这套系统从"能用"到"敢把电脑留在家里"的分水岭。**

### 12.2 两条路

| 路线 | 做法 | 成本 |
|---|---|---|
| **不改代码** | `hooks-claude-code` 的 `Stop` 钩子跑 `curl` | 只能拿到"回合结束"，拿不到"需要审批" |
| **写个小插件** ⭐ | host 插件订阅 `approval/request`、`user-questions/request`、回合结束，POST 到 ntfy | ~100 行，能力完整 |

```json
{ "hooks": { "Stop": [ { "type": "command",
  "command": "curl -s -d \"DSH 回合结束\" https://ntfy.sh/<随机topic> >/dev/null" } ] } }
```

插件版推送带 `sessionId` 做成**可点击直达**：

```
ntfy.sh/<随机topic>   内容: "🔔 需要你确认：rm -rf ... · 点开处理"
                      Click: https://dsh.example.com/#/session/<sessionId>
```

通道选型（安卓优先）：**ntfy**（开源、可自建、安卓 App 好、支持 Action 按钮直接批准/拒绝）⭐首选；**企业微信机器人**（你已有企业微信）；**Server 酱 / PushPlus**（走微信，零 App）。

> ⚠️ topic 名必须用**高熵随机串**当密码，否则任何人都能给你推垃圾。

### 12.3 与防窥的联动

通知插件同时也是 §8.3 防窥控制的载体——同一个插件里多注册一个 `screen.curtain` 命令。**一个插件解决"推送 + 防窥 + 唤醒引导"三件事。**

---

## 13. 无人值守：权限与安全

### 13.1 审批策略

| 场景 | 建议 |
|---|---|
| 你在旁边看着 | `ask` |
| **无人值守 + 希望它敢干活** | **Auto review**（每次工具调用前模型审查；放行→Full Access，拒绝→转问用户） |
| 完全无人 + 只要结果 | `never`（严格 headless 立场） |

⚠️ `never` 意味着审批类工具**静默失败**，任务可能半途而废。所以 `never` **必须**配推送。

**推荐组合**：`Auto review` + 推送 + 手机点开处理。

### 13.2 安全清单（这台是公司电脑，别跳过）

- [ ] **不暴露公网**：走 WireGuard/隧道；绝不做公网端口转发
- [ ] **Tailscale ACL** 只允许你这台手机访问该 Mac 的 3080
- [ ] **token 轮换**：手机上的 Cookie 是 30 天。手机丢失 → 立刻删 `~/.dsh/.credentials.yaml` 里的 `client-connection/browser-session` 记录，全部设备失效
- [ ] **手机本身**：锁屏密码 + 生物识别，最后一道门
- [ ] **工作区隔离**：远程会话用专门的工作目录，别让手机端直接摸公司代码库
- [ ] **精简远程工具**：你现在同时装了 UU远程、向日葵/RemoteMac、RustDesk、Windows App 四套（§2.6）。**每多一套就多一个攻击面**，建议只留 1–2 套
- [ ] **公司合规**：在装了 aTrust / Workspace ONE 的机器上开远程代码执行入口，先确认不违反 IT 政策

---

## 14. 分阶段落地路线图

| 阶段 | 内容 | 工作量 | 验收标准 |
|---|---|---|---|
| **Phase 0**<br>可行性验证 | ① 确认 `dsh` CLI 可用<br>② 打通隧道（测 Tailscale，不行换 frp）<br>③ 打信任围栏补丁<br>④ 手机浏览器打开现有 UI | **半天** | 手机上看到会话列表、发出第一条消息、看到流式输出 |
| **Phase 1**<br>常驻 + 电源 | launchd（`RunAtLoad + KeepAlive + LoginWindow`）<br>**`dsh-sleep-guard` 插件**（§7.3.1）<br>`womp 1` + `tcpkeepalive`<br>停掉常驻的 `codex.keep-awake`<br>日志/令牌落盘 | **1 天** | 空闲时 `pmset -g assertions` **无** 相关断言、机器正常入睡；发一条指令后断言**立刻出现**，跑完**立刻消失** |
| **Phase 2**<br>防窥 | 系统锁屏触发（自动/手动） | **半天** | 手机连上后本地屏幕锁定；本地必须输密码才能操作；远程不受影响 |
| **Phase 2.5**<br>唤醒 | 关闭私有 Wi-Fi 地址<br>记录稳定 MAC<br>WoL 发送端（辅助设备/路由器）<br>唤醒引导页 | **半天–1 天** | 让机器睡着 → 手机点"唤醒" → 60 秒内连上并跳转到会话列表 |
| **Phase 3**<br>手机体验 | `ui-mobile` 插件（单栏+抽屉）<br>safe-area / `visualViewport` / 触摸目标<br>PWA manifest + 图标 | **2–4 天** | 单手完成"看会话→发指令→看结果"；键盘不遮挡输入框；无横向滚动 |
| **Phase 4**<br>闭环 | notify 插件 + ntfy<br>Auto review 配置 | **1–2 天** | 锁屏状态下收到"需要确认"推送，点开直达并处理完成 |
| **Phase 5**<br>加固（可选） | 换 Mac mini 做真正 7×24<br>WoL 辅助设备<br>ACL / 密钥轮换 | 按需 | 断电重启后 5 分钟内自动恢复可远程 |

**每阶段可独立回滚**：Phase 0/1 只需删 launchd 和 patch；Phase 2 只需停用命令；Phase 3/4 只需移除插件行。

> **注意 Phase 2 提前了**：因为它的成本极低（零代码、半天），而收益很高。相比之下 UU 花了大量工程做自绘遮罩才达到更弱的效果。

---

## 15. 风险与"动手前必须实测"清单

| # | 待验证 | 不通过的后果 | 验证方式 |
|---|---|---|---|
| 1 | **Tailscale 是否可用 + 是否与 aTrust 冲突** | 整条链路不通 | 装完 ping 通 tailnet 另一台设备 |
| 2 | **`tailscale serve` 是否保留 Host/Origin** | 全部 `/api` 403 | `curl -v -H 'Origin: https://<名>' https://<名>/api/...` |
| 3 | **0.2.0 的 profile patch 语义**（是否整行替换 config） | patch 打歪、服务起不来 | `dsh --dump-config` 对比 patch 前后 |
| 4 | **`dsh` CLI 是否可用** | 无法跑独立常驻宿主 | `dsh --version` / `dsh web --help` |
| 5 | **MDM 是否允许改电源策略 / 装网络扩展** | Phase 1 直接卡死 | 系统设置里试改；`caffeinate` 试跑 |
| 6 | **桌面 App 与常驻宿主抢锁的实际表现** | 手机打不开某会话 | 两边同时打开同一会话，观察报错 |
| 7 | **L2 锁屏后宿主是否继续工作** | 防窥方案不成立 | 锁屏后从手机发一条指令，看是否正常执行 |
| 8 | **MIUI 对 PWA 后台的限制** | 推送延迟/丢失 | 息屏 30 分钟后收推送计时 |
| 9 | **私有 Wi-Fi 地址是否已关闭**（§9.2） | WoL 永远失败且难排查 | `ifconfig en0 \| grep ether` 与 `networksetup -getMACAddress Wi-Fi` 是否一致 |
| 10 | **家里有没有可当 WoL 发送端的常在线设备** | 远程唤醒只能靠 W1 自醒 | 检查路由器管理页（`192.168.10.1`）有无"网络唤醒"；或是否有 NAS/树莓派/常开电脑 |
| 11 | **`pmset schedule wake` 是否需要 sudo、能否与 Apple 自身 wake 事件共存** | 定时任务无法到点自醒 | `sudo pmset schedule wake "…"` 排一个 5 分钟后的时间，合盖等待验证 |
| 12 | **`dsh-sleep-guard` 的 busy 判定是否漏场景** | 干活干到一半机器睡了 | 跑一个长任务，期间用另一台设备确认机器未睡 |

> **第 1、5、7 条是方案成立与否的硬门槛**，建议今天就试。任何一条不过都要调整架构形态。
> **第 9 条最容易踩**：MAC 不一致会导致"配置全对但就是唤不醒"，而且现象极难定位。

---

## 16. 附录：关键位置索引

**本机电源 / 唤醒 实测证据（2026-09-30）**
- Wi-Fi 唤醒能力：`system_profiler SPAirPortDataType` → `en0: Wake On Wireless: Supported`
- 电源策略：`pmset -g custom` → AC `womp 1`、`tcpkeepalive 1`、`sleep 1`、`powernap 1`
- 已有 RTC 唤醒事件：`pmset -g sched` → `[0] wake at 10/01/2026 01:51:14`、`[1] wake at 07:52:14`
- 网络：Wi-Fi `en0`，`192.168.10.3`，网关 `192.168.10.1`
- **MAC 不一致（坑）**：`networksetup -getMACAddress Wi-Fi` = `a4:cf:99:5f:3e:af`；`ifconfig en0` = `7e:76:5e:29:e4:e1`
- 已存在的常驻断言：`pid 1621(caffeinate)`（来自 `com.openai.codex.keep-awake.plist`）、`pid 789(UURemote)`

**DSH 睡眠抑制钩子**
- `agent/status`（`idle ⇄ running`）：`packages/core/agent/src/runtime-types.ts:109, 277`
- `agent/created` / `agent/disposed`：同文件 `:265` 附近
- 活跃 root agent 枚举：`ctx.agents.roots()`，用法示例 `packages/schedule/schedule/src/index.ts:52`
- 后台 job：`packages/jobs/jobs/src/index.ts:90, 167`；`JobSnapshot.status` 定义 `packages/jobs/jobs/src/types.ts:97, 113`

**UU远程 证据位置**
- root 守护进程：`/Library/LaunchDaemons/com.netease.uuremote.daemon.plist`
- 登录窗口 Agent：`/Library/LaunchAgents/com.netease.uuremote.agent.plist`
- 电源断言实测：`pmset -g assertions` → `pid 789(UURemote) PreventUserIdleSystemSleep`
- 电源策略实测：`pmset -g custom` → AC `womp 1`
- 中文文案：`/Applications/UURemote.app/Contents/Resources/zh-Hans.lproj/Localizable.strings`
- 防窥实现类：`UURemoteServer` 符号表中 `PrivacyScreenManager`、`PrivacyScreenWindowLevelProtector`
- WoL 实现：`UURemote` 符号表中 `WakeOnLanManager.swift`、`NowDeviceWakeOnLan*`

**DSH 传输 / 鉴权**
- 信任围栏：`packages/client/connection/src/api-request-trust.ts:12-18, 91-117`
- Token→Cookie：`packages/client/connection/src/browser-auth.ts:12-14, 121-132, 223-282`
- WS 多路复用：`packages/api/gateway/src/stream-protocol.ts:6, 243-263`

**宿主 / 绑定**
- 绑地址联合类型：`packages/host/webserver/src/index.ts:61, 126, 294`
- 拒绝 `0.0.0.0`：`packages/bundle/web-app/src/startup.ts:74-76`
- 默认 host/port：`packages/bundle/web-app/cordis.patch.yml:135-143`
- LAN 信任自动采样：`packages/bundle/web-app/src/index.ts:125-132`
- `trustedHosts` 行：`packages/bundle/web-app/cordis.patch.yml:180-189`

**会话 / 并发**
- 排他租约：`packages/session/session-persistence-jsonl/src/lease.ts:1-27, 40, 70-135`
- 落盘格式：`packages/session/session-persistence-jsonl/src/format.ts:82-97, 224-268`
- resume：`packages/core/agent-loop/src/index.ts:875-901`
- fork：`packages/api/session-controller/src/commands.ts:202-295`
- `follow` 快照+续传：`packages/api/session-controller/src/{index,history}.ts`

**无人值守**
- 审批策略：`packages/interaction/user-approval/src/index.ts:51-60, 88-97, 118-125`
- goal driver：`packages/goal/goal-round-driver/src/index.ts:103-109, 138-205, 428-433`
- schedule：`docs/subsystems/schedule.md:67-105, 186-190`
- 钩子事件：`packages/hooks/hooks-claude-code/src/config.ts:12-16`

**客户端 UI**
- 断点/最小宽：`packages/client/ui-layout/src/client/columns.ts:11-25`
- 槽位注册：`packages/client/ui-slots/src/index.ts:780, 807`
- 会话/输入框槽位：`packages/client/ui-conversation/src/client/contract/slots.ts:121-158`
- 客户端打包：`packages/client/tsdown.client.ts:489-499, 559-568`
- 客户端 roster：`packages/bundle/web-app/cordis.patch.yml:255+`

---

*文档基于 2026-09-30 对 `~/Work/deepseek-harness`（0.1.5-rc.2）源码、已安装 `DeepSeek Harness 0.2.0-rc.2`、以及已安装 `UURemote`（`com.netease.uuremote`）的实机核查。所有"实测"结论均来自本机命令输出；版本落差见 §3.3。*
