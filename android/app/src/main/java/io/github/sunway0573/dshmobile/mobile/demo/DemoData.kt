package io.github.sunway0573.dshmobile.mobile.demo

import io.github.sunway0573.dshmobile.mobile.model.AuthState
import io.github.sunway0573.dshmobile.mobile.model.ComputerRecord
import io.github.sunway0573.dshmobile.mobile.model.MobileTarget

/**
 * Local sample content for reviewing the interface.
 *
 * ## What this is and is not
 *
 * This is not a backend, a cache, or a fallback. Nothing here is ever presented
 * as a real result: the screens that use it carry a visible banner saying so,
 * because an interface that shows invented tasks without saying they are
 * invented is worse than one that shows nothing — it teaches the user to trust
 * numbers that came from nowhere.
 *
 * It exists because the alternative is wiring the transport first and designing
 * the screens against whatever the transport happened to make easy. The plan is
 * explicit that the interface is agreed before the APIs are connected, and this
 * is how that is done without a computer present.
 *
 * Every id is prefixed `demo-` so a leaked sample cannot be mistaken for a real
 * computer in a bug report.
 */
internal object DemoData {

    const val MAC = "demo-mac"
    const val MINI = "demo-mini"

    /** Two computers with deliberately identical session ids. */
    val computers = listOf(
        ComputerRecord(
            computerId = MAC,
            alias = "我的 Mac",
            identityFingerprint = "a4:cf:99:5f:3e:af",
            endpoints = listOf("https://mac.example.ts.net"),
            authState = AuthState.AUTHORIZED,
            lastSeenEpochMs = null,
        ),
        ComputerRecord(
            computerId = MINI,
            alias = "办公室的 Mac mini",
            identityFingerprint = "58:b6:23:46:e0:65",
            endpoints = listOf("https://mini.example.ts.net"),
            authState = AuthState.AUTHORIZED,
            lastSeenEpochMs = null,
        ),
    )

    /**
     * The same `sessionId` on both computers, on purpose.
     *
     * It is the condition the multi-computer model exists for, and a demo that
     * used different ids would let a mixing bug look correct on screen.
     */
    val firstTarget = MobileTarget(MAC, "session-1")

    data class DemoTask(
        val target: MobileTarget,
        val title: String,
        val subtitle: String,
        val status: TaskStatus,
    )

    enum class TaskStatus { RUNNING, WAITING_APPROVAL, DONE, STOPPED }

    val tasks = listOf(
        DemoTask(firstTarget, "整理下载目录中的 PDF", "正在运行 · 已处理 12 / 30 个文件", TaskStatus.RUNNING),
        DemoTask(firstTarget, "把季度报表转成 PDF", "需要你确认一步操作", TaskStatus.WAITING_APPROVAL),
        DemoTask(MobileTarget(MAC, "session-2"), "检查 coupon 项目的依赖", "已完成 · 9 分钟前", TaskStatus.DONE),
        DemoTask(MobileTarget(MAC, "session-3"), "团队协同插件使用说明", "已停止 · 昨天 18:20", TaskStatus.STOPPED),
        // Same session id as the first task, on the other computer.
        DemoTask(MobileTarget(MINI, "session-1"), "整理下载目录中的 PDF", "已完成 · 2 天前", TaskStatus.DONE),
    )

    data class DemoMessage(val fromMe: Boolean, val text: String, val code: String? = null, val meta: String? = null)

    val conversation = listOf(
        DemoMessage(false, "整理下载目录中的 PDF，先给我分类预览"),
        DemoMessage(
            false,
            "找到了 30 个 PDF，按内容分成了 4 类：\n\n· 发票与报销 11 个\n· 合同与协议 7 个\n" +
                "· 产品资料 8 个\n· 其他 4 个\n\n需要我建 4 个子目录把它们移过去吗？移动前会先列出清单。",
            meta = "12 秒前",
        ),
        DemoMessage(
            false,
            "正在读取文件内容…",
            code = "$ pdftotext -f 1 -l 1 发票_2026_03.pdf -\n发票号码: 04412…\n开票日期: 2026-03-14",
            meta = "进行中 · 12/30",
        ),
    )

    /** The approval the demo shows, with its impact spelled out. */
    val approvalCommand = """
        mkdir -p ~/Downloads/报表/2026-Q3
        mv ~/Downloads/Q3*.xlsx ~/Downloads/报表/2026-Q3/
        soffice --headless --convert-to pdf ~/Downloads/报表/2026-Q3/*.xlsx
    """.trimIndent()

    val approvalImpact = "在下载目录下新建一个文件夹，移动 3 个 Excel 文件进去，" +
        "然后在原位置生成同名 PDF。不会删除任何文件。"
}
