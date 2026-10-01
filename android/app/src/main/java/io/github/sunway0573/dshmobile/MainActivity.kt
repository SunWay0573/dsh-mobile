package io.github.sunway0573.dshmobile

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.sunway0573.dshmobile.mobile.protocol.ComputerStatus
import io.github.sunway0573.dshmobile.mobile.protocol.MobileProtocol
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.Scope
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState
import io.github.sunway0573.dshmobile.mobile.repository.SessionRepository
import io.github.sunway0573.dshmobile.mobile.transport.FixtureTransport
import io.github.sunway0573.dshmobile.mobile.transport.TransportResult
import io.github.sunway0573.dshmobile.mobile.ui.MobileApp
import io.github.sunway0573.dshmobile.mobile.ui.UiState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/**
 * The whole app: a native shell around the three things a WebView cannot do —
 * remembering where the host is, waking a sleeping machine, and gating access
 * behind a biometric.
 *
 * Deliberately one activity and no navigation library. There are two screens,
 * and the session screen is a WebView that owns its own history; a navigation
 * graph would add a dependency and a second place for back handling to go wrong.
 *
 * Extends [FragmentActivity] because `BiometricPrompt` requires one. Anonymous
 * screens back both the home screen and the session list; there is no reason to
 * build a native session list, because that means reimplementing DSH's RPC
 * protocol and cookie exchange to render a screen the web client already
 * renders correctly. Native owns what the host cannot do; the WebView owns
 * everything that comes from the host.
 */
class MainActivity : FragmentActivity() {

    /**
     * A session id from a notification tap, waiting to be handed to the UI.
     *
     * Held as state rather than read once in `onCreate`, because a tap while the
     * app is already running arrives through `onNewIntent` and has to reach a
     * composition that is already up.
     */
    private val pendingSession = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSession.value = DeepLinks.sessionId(intent?.data?.toString())

        // `targetSdk 35` draws edge to edge, so the app owns the area behind the
        // status bar. The bars' icons default to light, which on this light
        // background is white-on-white — the clock and battery were unreadable
        // in the first device screenshots. Asking for dark icons fixes the
        // contrast; insetting the content fixes the overlap. Doing only one of
        // the two leaves the other problem.
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = true

        val activity = this
        setContent {
            MaterialTheme {
                val settings = remember { AppSettings(applicationContext) }
                val biometricPossible = remember { canAuthenticate(applicationContext) }
                // promptOnStart only on a cold start: re-prompting on every
                // rotation would be hostile, and the unlocked state was never
                // lost in the first place.
                val coldStart = remember { savedInstanceState == null }

                BiometricGate(
                    activity = activity,
                    enabled = settings.lockWithBiometric && biometricPossible,
                    promptOnStart = coldStart,
                ) {
                    DshMobileApp(settings, biometricPossible, pendingSession)
                }
            }
        }
    }

    /**
     * A notification tap while the app is already running.
     *
     * `singleTop` in the manifest routes it here rather than starting a second
     * copy. `setIntent` matters too: without it a later `getIntent()` still
     * reports the launch intent, and the tap appears to do nothing the second
     * time.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSession.value = DeepLinks.sessionId(intent.data?.toString())
    }
}

/**
 * What to say about the lock, given whether this device can prompt at all.
 *
 * Extracted rather than inlined as an `if` argument: a block whose continuation
 * lines begin with `+` parses as a unary plus on a new statement, not as string
 * concatenation. The compiler says "unresolved reference 'unaryPlus'", which is
 * a correct description of a wrong parse and no help at all in finding it.
 */
private fun securityNote(biometricPossible: Boolean): String =
    if (biometricPossible) {
        "This app holds a sign-in cookie for a machine that can run arbitrary code. " +
            "Requiring an unlock means a phone left unlocked on a table is not the " +
            "same as handing over the computer. It does not ask for your device PIN, " +
            "because that PIN is what unlocks the phone anyway."
    } else {
        "This device has no biometric enrolled, so the lock cannot be enabled. " +
            "Add a fingerprint in system settings first."
    }

/**
 * Pins the document to the WebView's real height, in pixels.
 *
 * ## The bug this exists for
 *
 * On a real device the session screen rendered **blank white** while the page
 * itself was perfect: `readyState === "complete"`, `#root` held 35 000 characters
 * of DOM, and the DSH UI was fully built -- with every element measuring zero
 * height.
 *
 * The cause is that this WebView's CSS viewport height is 0 even though the view
 * is full-screen and `window.innerHeight` reports 853. Both `100vh` and
 * `height: 100%` therefore resolve to 0, so DSH's own
 * `html, body, #root { height: 100% }` collapses the whole layout. Measured on a
 * Redmi Note 15 Pro:
 *
 *     html:100%  -> frame 0     html:100vh -> frame 0     html:853px -> frame 853
 *
 * `100vh` failing is the tell: it takes no parent into account, so nothing about
 * DSH's markup can explain it.
 *
 * ## Why a pixel height rather than a percentage
 *
 * Because the percentage is the thing that is broken, and this is measured rather
 * than assumed. Setting `html` alone is enough -- body and `#root` then resolve
 * their own `height: 100%` against it and come out at 853 too, which was checked
 * before relying on it.
 *
 * ## What is not known
 *
 * The mechanism. Chromium draws 853 px of content into a viewport it simultaneously
 * reports as zero-height, and forcing a reload with the view already sized does
 * not change it. `useWideViewPort` was tried and made no difference. This is a
 * workaround for an observed behaviour, not a fix derived from a cause, and the
 * comment says so rather than implying more confidence than there is.
 */
private const val WEBVIEW_HEIGHT_FIX = """
(function () {
  var apply = function () {
    var h = window.innerHeight;
    if (!h) return;
    document.documentElement.style.setProperty('height', h + 'px', 'important');
  };
  apply();
  window.addEventListener('resize', apply);
  window.addEventListener('orientationchange', function () { setTimeout(apply, 150); });
  if (window.visualViewport) { window.visualViewport.addEventListener('resize', apply); }
})();
"""

/**
 * Temporary overlay layout for the desktop sidebar on a phone.
 *
 * ## The problem, measured on the device
 *
 * Below 1024px DSH collapses the sidebar to a 56px rail, and hiding the right
 * bar gives the conversation a usable 334px. **Expanding** the sidebar is what
 * breaks: the column solver hands it its 264px minimum, leaving about 110px for
 * the conversation, and Chinese text wraps to one character per line. Screenshot
 * from a Redmi Note 15 Pro at 394dp: `evidence/05-session-navigation.png`.
 *
 * ## Why this is a bounded patch and not a redesign
 *
 * Navigation should not squeeze the content it navigates — on a phone the
 * sidebar belongs *over* the conversation, not beside it. That is a one-rule
 * change, so it is done here and stops here.
 *
 * This is explicitly a stopgap for the author's own use. It patches someone
 * else's DOM through CSS-module class fragments, which is fragile by nature, and
 * it will be deleted when the native mobile interface lands rather than grown.
 * Do not add further rules to it.
 */
private const val MOBILE_LAYOUT_FIX = """
(function () {
  var id = 'dsh-mobile-layout';
  if (document.getElementById(id)) return 'already-applied';

  var style = document.createElement('style');
  style.id = id;
  style.textContent = [
    '@media (max-width: 600px) {',
    // Expanded sidebar: give it no grid column at all, so the conversation keeps
    // the full width behind it.
    '  [class*="_frame"]:not([data-sidebar-collapsed]) {',
    '    grid-template-columns: 0 minmax(0, 1fr) 0 !important;',
    '  }',
    // ...and float the sidebar on top instead.
    '  [class*="_frame"]:not([data-sidebar-collapsed]) [class*="_sidebarCol"] {',
    '    position: absolute !important;',
    '    top: 0; bottom: 0; left: 0;',
    '    width: min(86vw, 360px) !important;',
    '    z-index: 40;',
    '    background: var(--dsw-alias-bg-base, Canvas);',
    '    box-shadow: 0 8px 32px rgb(0 0 0 / 0.18);',
    '  }',
    '}'
  ].join('\n');
  (document.head || document.documentElement).appendChild(style);
  return 'applied';
})()
"""

/**
 * Asks the page whether the DSH client actually mounted.
 *
 * `onPageFinished` means the document finished loading. It says nothing about
 * whether the scripts inside it ran — and it fires just as happily for the error
 * page a 403 renders. Reporting ready on that basis is how a user ends up on a
 * blank screen with the app insisting everything is fine.
 *
 * The shell element is the check rather than `#root` having children, because
 * the boot placeholder also renders into `#root`: it is present on a page whose
 * client never started, which is exactly the state being tested for.
 */
private const val CLIENT_MOUNT_PROBE = """
(function () {
  var root = document.getElementById('root');
  if (!root || root.children.length === 0) return false;
  return !!document.querySelector('[class*="_frame"]');
})()
"""

/** How many times to ask before deciding the client is not coming. */
private const val MOUNT_PROBE_ATTEMPTS = 12

/** Gap between probes: 12 x 400ms is about five seconds, generous for a cold load. */
private const val MOUNT_PROBE_INTERVAL_MS = 400L

/**
 * Poll the page until the client has mounted, or give up and say so.
 *
 * Give up loudly rather than silently: "the page loaded but the application did
 * not start" is a different problem from "the host is unreachable", and the two
 * need different actions from the user.
 *
 * @param view the WebView to ask.
 * @param load the current load's outcome, so a failure reported while probing is
 *   not overwritten by this probe's answer.
 * @param onState where to report.
 * @param attempt how many probes have already run.
 */
private fun probeClientStart(
    view: WebView,
    load: SessionLoad,
    onState: (WebState) -> Unit,
    attempt: Int,
) {
    view.evaluateJavascript(CLIENT_MOUNT_PROBE) { result ->
        // This load already failed (an HTTP error arrived mid-probe); that
        // verdict stands and is more informative than anything here.
        if (!load.mayReportReady()) return@evaluateJavascript

        if (result == "true") {
            onState(WebState.Ready)
            return@evaluateJavascript
        }
        if (attempt >= MOUNT_PROBE_ATTEMPTS) {
            onState(WebErrors.forClientDidNotStart())
            return@evaluateJavascript
        }
        view.postDelayed(
            { probeClientStart(view, load, onState, attempt + 1) },
            MOUNT_PROBE_INTERVAL_MS,
        )
    }
}

/** Which screen is showing. */
private enum class Screen { Home, LegacyHome, Session }

@Composable
private fun DshMobileApp(
    settings: AppSettings,
    biometricPossible: Boolean,
    pendingSession: MutableState<String?>,
) {
    var screen by remember { mutableStateOf(Screen.Home) }
    // The URL the session screen should load. Normally the host root, but a
    // notification tap points it at one session.
    var sessionTarget by remember { mutableStateOf("") }
    var deepLinkNotice by remember { mutableStateOf<String?>(null) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var webState by remember { mutableStateOf<WebState>(WebState.Loading) }

    var hostUrl by remember { mutableStateOf(settings.hostUrl) }
    var wakeMac by remember { mutableStateOf(settings.wakeMac) }
    var wakeBroadcast by remember { mutableStateOf(settings.wakeBroadcast) }
    var wakeBridgeUrl by remember { mutableStateOf(settings.wakeBridgeUrl) }
    var wakeBridgeToken by remember { mutableStateOf(settings.wakeBridgeToken) }
    var lockWithBiometric by remember { mutableStateOf(settings.lockWithBiometric) }
    var wakeStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // One handler, so precedence never depends on composition order: walk the
    // WebView's history first, and only leave the screen when there is nowhere
    // back to go.
    BackHandler(enabled = screen == Screen.Session) {
        val view = activeWebView
        if (view != null && view.canGoBack()) view.goBack() else screen = Screen.Home
    }

    // A notification tap. The host address comes from settings, because the app
    // is the only party that knows it.
    val requestedSession = pendingSession.value
    LaunchedEffect(requestedSession) {
        if (requestedSession == null) return@LaunchedEffect
        pendingSession.value = null
        val target = DeepLinks.sessionUrl(settings.hostUrl, requestedSession)
        if (target == null) {
            deepLinkNotice = "Open a session once to set the host address, then a " +
                "notification tap will go straight there."
        } else {
            sessionTarget = target
            webState = WebState.Loading
            screen = Screen.Session
        }
    }

    // 手机端界面现在是默认入口。原来的网页界面降级为诊断页里的调试入口——
    // 它展示的是桌面布局，而且用的是宿主自己的 Cookie，不属于独立设备授权。
    // 按计划，独立设备授权版本落地时这个入口会被彻底移除。
    // 协商结果驱动界面状态。
    //
    // 目前背后是一个固定装置：真实传输属于工作包3 的下一段。但这条路径本身是真的——
    // 状态由 MobileAdapter 的应答经 negotiate() 算出，而不是写死的 UiState.NORMAL。
    // 诊断页显示的版本号和能力数就来自这里，所以如果协商逻辑坏了，它会显示出来，
    // 而不是继续显示一个好看的数字。
    var negotiated by remember { mutableStateOf<ComputerState>(ComputerState.Offline("尚未连接")) }
    val repository = remember {
        SessionRepository(
            FixtureTransport(
                TransportResult.Ok(
                    ComputerStatus(
                        protocolVersion = MobileProtocol.VERSION,
                        adapterVersion = "1.0.0",
                        hostVersion = "0.2.0-rc.2（示例）",
                        computerId = "demo-mac",
                        computerName = "我的 Mac",
                        capabilities = Operation.entries.toSet(),
                        grantedScopes = Scope.entries.toSet(),
                    ),
                ),
            ),
        )
    }
    LaunchedEffect(repository) { negotiated = repository.refresh() }

    when (screen) {
        Screen.Home -> MobileApp(
            state = UiState.NORMAL,
            computerState = negotiated,
            wakeConfig = settings.wakeConfig,
            onSaveWakeConfig = { settings.saveWakeConfig(it) },
            onOpenLegacyWebView = {
                sessionTarget = ""
                webState = WebState.Loading
                screen = Screen.Session
            },
        )

        Screen.LegacyHome -> HomeScreen(
            hostUrl = hostUrl,
            onHostUrlChange = { hostUrl = it },
            wakeMac = wakeMac,
            onWakeMacChange = { wakeMac = it },
            wakeBroadcast = wakeBroadcast,
            onWakeBroadcastChange = { wakeBroadcast = it },
            wakeBridgeUrl = wakeBridgeUrl,
            onWakeBridgeUrlChange = { wakeBridgeUrl = it },
            wakeBridgeToken = wakeBridgeToken,
            onWakeBridgeTokenChange = { wakeBridgeToken = it },
            lockWithBiometric = lockWithBiometric,
            biometricPossible = biometricPossible,
            onLockWithBiometricChange = {
                lockWithBiometric = it
                settings.lockWithBiometric = it
            },
            wakeStatus = wakeStatus,
            deepLinkNotice = deepLinkNotice,
            onOpenSession = {
                sessionTarget = ""
                webState = WebState.Loading
                screen = Screen.Session
            },
            onSaveHost = { settings.hostUrl = hostUrl },
            hostSaved = hostUrl == settings.hostUrl,
            onWake = {
                settings.wakeMac = wakeMac
                settings.wakeBroadcast = wakeBroadcast
                settings.wakeBridgeUrl = wakeBridgeUrl
                settings.wakeBridgeToken = wakeBridgeToken
                val plan = Wake.plan(wakeBridgeUrl, wakeBridgeToken, wakeMac, wakeBroadcast)
                scope.launch { wakeStatus = WakeOnLan.attempt(plan) }
            },
        )

        Screen.Session -> SessionScreen(
            url = sessionTarget.ifEmpty { hostUrl },
            state = webState,
            onState = { webState = it },
            onCreated = { activeWebView = it },
        )
    }
}

@Composable
private fun HomeScreen(
    hostUrl: String,
    onHostUrlChange: (String) -> Unit,
    wakeMac: String,
    onWakeMacChange: (String) -> Unit,
    wakeBroadcast: String,
    onWakeBroadcastChange: (String) -> Unit,
    wakeBridgeUrl: String,
    onWakeBridgeUrlChange: (String) -> Unit,
    wakeBridgeToken: String,
    onWakeBridgeTokenChange: (String) -> Unit,
    lockWithBiometric: Boolean,
    biometricPossible: Boolean,
    onLockWithBiometricChange: (Boolean) -> Unit,
    wakeStatus: String?,
    deepLinkNotice: String?,
    onOpenSession: () -> Unit,
    onSaveHost: () -> Unit,
    hostSaved: Boolean,
    onWake: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("dsh-mobile", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Remote control for a DeepSeek Harness agent on your own machine.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = onOpenSession,
                enabled = hostUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open session") }

            if (hostUrl.isBlank()) {
                Text(
                    "Set the host address below first.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            deepLinkNotice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Host", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = hostUrl,
                        onValueChange = onHostUrlChange,
                        label = { Text("Address") },
                        placeholder = { Text("machine.tailnet.ts.net") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = onSaveHost,
                        enabled = !hostSaved,
                    ) { Text("Save host") }
                    Text(
                        "Point this at whatever your tunnel exposes. DSH prints a one-time "
                            + "sign-in link on every start: open that link once on this device, "
                            + "or the session screen will show an authentication error.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Wake the computer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A sleeping machine cannot send its own wake packet, and magic "
                            + "packets do not cross routers. A direct broadcast therefore "
                            + "only works from the same network; from anywhere else you "
                            + "need a wol-bridge on your LAN.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    // Say which path will be used, because the wrong one fails
                    // silently: the packet is sent, nothing complains, and the
                    // machine simply never wakes.
                    Text(
                        when (Wake.plan(wakeBridgeUrl, wakeBridgeToken, wakeMac, wakeBroadcast)) {
                            is WakePlan.ViaBridge ->
                                "Will ask the bridge over the network. This works from anywhere."
                            is WakePlan.ViaBroadcast ->
                                "Will broadcast on the local network. This only works while the " +
                                    "phone is on the same network as the machine."
                            WakePlan.NotConfigured ->
                                "Nothing configured yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    OutlinedTextField(
                        value = wakeBridgeUrl,
                        onValueChange = onWakeBridgeUrlChange,
                        label = { Text("Bridge address (preferred)") },
                        placeholder = { Text("http://127.0.0.1:8787") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = wakeBridgeToken,
                        onValueChange = onWakeBridgeTokenChange,
                        label = { Text("Bridge shared secret") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Leave the bridge blank to use a direct broadcast instead.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    OutlinedTextField(
                        value = wakeMac,
                        onValueChange = onWakeMacChange,
                        label = { Text("MAC address") },
                        placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = wakeBroadcast,
                        onValueChange = onWakeBroadcastChange,
                        label = { Text("Broadcast address") },
                        placeholder = { Text("192.168.1.255") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onWake,
                            enabled = wakeBridgeUrl.isNotBlank() ||
                                (wakeMac.isNotBlank() && wakeBroadcast.isNotBlank()),
                        ) { Text("Wake") }
                    }
                    wakeStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Security", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Require unlock", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = lockWithBiometric,
                            onCheckedChange = onLockWithBiometricChange,
                            enabled = biometricPossible,
                        )
                    }
                    Text(
                        securityNote(biometricPossible),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * The session, rendered by DSH's own web client.
 *
 * Loading the host's UI rather than reimplementing it is the point: the
 * transcript, tool-call cards, approval prompts and composer stay identical to
 * the desktop and keep working when DSH updates, with nothing here to maintain.
 *
 * @param url the host address; the caller gates on it being non-blank.
 * @param state the state to render a banner for.
 * @param onState reports load progress and failure.
 * @param onCreated receives the WebView so the host screen can drive back
 *   navigation and retries.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SessionScreen(
    url: String,
    state: WebState,
    onState: (WebState) -> Unit,
    onCreated: (WebView) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The client is created once inside `factory`, so it would otherwise capture
    // the first `onState` forever and write into a stale closure.
    val reportState by rememberUpdatedState(onState)
    val reportCreated by rememberUpdatedState(onCreated)
    // One load's outcome, shared by the callbacks below. Without it a late
    // `onPageFinished` overwrites the failure that `onReceivedHttpError` just
    // reported, and the user is left on the host's error page while the app
    // insists everything is fine.
    val load = remember { SessionLoad() }

    // `targetSdk 35` means Android 15+ draws the app edge to edge whether it asks
    // to or not, so without this the DSH UI's top row sits underneath the status
    // bar -- visible on a real device as the first sidebar icon overlapping the
    // clock. Padding here rather than in the page because DSH's viewport meta has
    // no `viewport-fit=cover`, so `env(safe-area-inset-*)` is 0 inside it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (state) {
            is WebState.Failed -> FailureBanner(
                message = state.message,
                onRetry = { webView?.reload() },
            )

            WebState.Loading -> Text(
                "Contacting the host…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            WebState.Ready -> Unit
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    // DSH's client keeps its session cookie and cached plugin
                    // bundles in DOM storage; without this it re-bootstraps from
                    // scratch on every launch.
                    settings.domStorageEnabled = true

                    // Honour the page's own
                    //   <meta name="viewport" content="width=device-width, initial-scale=1">
                    // Correct on its own terms. It is NOT what fixes the collapse
                    // described at WEBVIEW_HEIGHT_FIX below -- that was tried and
                    // changed nothing.
                    settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            startedUrl: String?,
                            favicon: Bitmap?,
                        ) {
                            load.started()
                        }

                        override fun onPageFinished(view: WebView, loadedUrl: String?) {
                            // See WEBVIEW_HEIGHT_FIX. Re-applied on every load,
                            // because a reload produces the same collapsed layout.
                            view.evaluateJavascript(WEBVIEW_HEIGHT_FIX, null)
                            view.evaluateJavascript(MOBILE_LAYOUT_FIX, null)

                            // A finished page is not a working application. This
                            // callback also fires for the error page a 403 renders,
                            // and for a page whose scripts then throw.
                            if (!load.mayReportReady()) return

                            // So ask the page whether the client actually came up,
                            // rather than inferring it from the document finishing.
                            probeClientStart(view, load, reportState, attempt = 0)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame) return
                            load.failed()
                            reportState(WebErrors.forTransport(error.description.toString()))
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            response: WebResourceResponse,
                        ) {
                            // Main frame only. DSH streams plugin bundles and
                            // optional assets, and raising a banner over a page
                            // that is working because one of them 404s would be
                            // worse than saying nothing.
                            if (!request.isForMainFrame) return
                            WebErrors.forStatus(response.statusCode)?.let {
                                load.failed()
                                reportState(it)
                            }
                        }
                    }
                    webView = this
                    reportCreated(this)
                }
            },
        )

        // One place loads the URL, so a *changed* url navigates the WebView that
        // already exists. The factory used to call `loadUrl` itself, which meant
        // a notification tap arriving while this screen was already composed set
        // a new target that nothing ever acted on -- the tap appeared to do
        // nothing at all.
        LaunchedEffect(url, webView) {
            val view = webView ?: return@LaunchedEffect
            load.started()
            reportState(WebState.Loading)
            view.loadUrl(Urls.normalize(url))
        }
    }

    // A tunnel that was down when the app went to the background is very often
    // back by the time it returns. The web client handles its own socket
    // recovery; what it cannot do is notice that the page never loaded in the
    // first place.
    val lifecycleOwner = LocalLifecycleOwner.current
    // `state` is a plain parameter, so an effect keyed only on the lifecycle
    // owner captures whatever it was at the first composition -- `Loading` --
    // and the check below then never fires again. This app has been bitten by
    // the same stale-closure shape three times; `rememberUpdatedState` is the
    // fix each time.
    val currentState by rememberUpdatedState(state)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentState is WebState.Failed) {
                webView?.reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun FailureBanner(message: String, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Not connected", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
