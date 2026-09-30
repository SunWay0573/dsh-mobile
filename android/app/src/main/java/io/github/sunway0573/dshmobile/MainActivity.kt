package io.github.sunway0573.dshmobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * The whole app: a native shell around the two things a WebView cannot do —
 * remembering where the host is, and waking a sleeping machine.
 *
 * Deliberately one activity and no navigation library. There are two screens,
 * and the session screen is a WebView that owns its own history; a navigation
 * graph would add a dependency and a second place for back handling to go
 * wrong.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DshMobileApp(AppSettings(applicationContext))
            }
        }
    }
}

/** Which screen is showing. */
private enum class Screen { Home, Session }

@Composable
private fun DshMobileApp(settings: AppSettings) {
    var screen by remember { mutableStateOf(Screen.Home) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    var hostUrl by remember { mutableStateOf(settings.hostUrl) }
    var wakeMac by remember { mutableStateOf(settings.wakeMac) }
    var wakeBroadcast by remember { mutableStateOf(settings.wakeBroadcast) }
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
            wakeStatus = wakeStatus,
            onOpenSession = { screen = Screen.Session },
            onSaveHost = { settings.hostUrl = hostUrl },
            hostSaved = hostUrl == settings.hostUrl,
            onWake = {
                settings.wakeMac = wakeMac
                settings.wakeBroadcast = wakeBroadcast
                scope.launch {
                    wakeStatus = try {
                        WakeOnLan.wake(wakeMac, wakeBroadcast)
                        "Wake packet sent. Give the machine up to a minute."
                    } catch (error: IllegalArgumentException) {
                        "Check the address: ${error.message}"
                    } catch (error: Exception) {
                        "Could not send: ${error.message}"
                    }
                }
            },
        )

        Screen.Session -> SessionScreen(
            url = hostUrl,
            onReady = { activeWebView = it },
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
                            + "packets do not cross routers. This works when the phone is on "
                            + "the same network; from anywhere else you need a wol-bridge on "
                            + "your LAN.",
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
                            enabled = wakeMac.isNotBlank() && wakeBroadcast.isNotBlank(),
                        ) { Text("Wake") }
                    }
                    wakeStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * The conversation, rendered by DSH's own web client.
 *
 * Loading the host's UI rather than reimplementing it is the point: the
 * transcript, tool-call cards, approval prompts and composer stay identical to
 * the desktop and keep working when DSH updates, with nothing here to maintain.
 *
 * @param url the host address; the caller gates on it being non-blank.
 * @param onReady receives the WebView so the host screen can drive back
 *   navigation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SessionScreen(url: String, onReady: (WebView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // DSH's client keeps its session cookie and cached plugin
                // bundles in DOM storage; without this it re-bootstraps from
                // scratch on every launch.
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(Urls.normalize(url))
                onReady(this)
            }
        },
    )
}
