package io.github.sunway0573.dshmobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    DshMobileApp(settings, biometricPossible)
                }
            }
        }
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

/** Which screen is showing. */
private enum class Screen { Home, Session }

@Composable
private fun DshMobileApp(settings: AppSettings, biometricPossible: Boolean) {
    var screen by remember { mutableStateOf(Screen.Home) }
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

    when (screen) {
        Screen.Home -> HomeScreen(
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
            onOpenSession = {
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
            url = hostUrl,
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

    Column(modifier = Modifier.fillMaxSize()) {
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
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String?) {
                            reportState(WebState.Ready)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame) return
                            reportState(
                                WebState.Failed(
                                    WebErrors.forNetworkError(error.description.toString()),
                                ),
                            )
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
                            WebErrors.forHttpStatus(response.statusCode)
                                ?.let { reportState(WebState.Failed(it)) }
                        }
                    }
                    loadUrl(Urls.normalize(url))
                    webView = this
                    reportCreated(this)
                }
            },
        )
    }

    // A tunnel that was down when the app went to the background is very often
    // back by the time it returns. The web client handles its own socket
    // recovery; what it cannot do is notice that the page never loaded in the
    // first place.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state is WebState.Failed) {
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
