package io.github.sunway0573.dshmobile

import android.content.Context

/**
 * Where the host lives, and how to wake it.
 *
 * `SharedPreferences` rather than DataStore: there are three values, they are
 * read once at startup, and a synchronous read is what the UI wants. Pulling in
 * a persistence library for this would be more moving parts than the problem
 * has.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Base URL of the DSH host, e.g. `https://machine.tailnet.ts.net`. */
    var hostUrl: String
        get() = prefs.getString(KEY_HOST_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST_URL, value.trim()).apply()

    /** MAC address of the machine to wake, in any form [WakeOnLan] accepts. */
    var wakeMac: String
        get() = prefs.getString(KEY_WAKE_MAC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_MAC, value.trim()).apply()

    /** Broadcast address of that machine's subnet, e.g. `192.168.1.255`. */
    var wakeBroadcast: String
        get() = prefs.getString(KEY_WAKE_BROADCAST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_BROADCAST, value.trim()).apply()

    /**
     * Base URL of a `wol-bridge` on your LAN, e.g. `http://127.0.0.1:8787`
     * behind the same tunnel as the host.
     *
     * This is the only wake path that works when the phone is away from home,
     * which is the situation the feature exists for. A direct broadcast only
     * reaches the same subnet.
     */
    var wakeBridgeUrl: String
        get() = prefs.getString(KEY_WAKE_BRIDGE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_BRIDGE_URL, value.trim()).apply()

    /** Shared secret for that bridge, when it requires one. */
    var wakeBridgeToken: String
        get() = prefs.getString(KEY_WAKE_BRIDGE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_BRIDGE_TOKEN, value.trim()).apply()

    /**
     * Require a biometric before showing anything.
     *
     * Off by default: a lock the user did not ask for is a lock they will
     * disable, and this app is useless if it is annoying. The settings screen
     * explains what it protects.
     */
    var lockWithBiometric: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    /**
     * The wake configuration as one value.
     *
     * The four settings already existed individually; this groups them so the
     * diagnostics screen can load, diff and save them as a unit. Saving them
     * individually looked fine and meant a half-saved configuration — a new MAC
     * with the old broadcast address — was possible between two writes.
     */
    internal val wakeConfig: io.github.sunway0573.dshmobile.mobile.ui.WakeConfig
        get() = io.github.sunway0573.dshmobile.mobile.ui.WakeConfig(
            mac = wakeMac,
            broadcast = wakeBroadcast,
            relayUrl = wakeBridgeUrl,
            relayToken = wakeBridgeToken,
        )

    /** Persist a whole wake configuration. */
    internal fun saveWakeConfig(config: io.github.sunway0573.dshmobile.mobile.ui.WakeConfig) {
        wakeMac = config.mac
        wakeBroadcast = config.broadcast
        wakeBridgeUrl = config.relayUrl
        wakeBridgeToken = config.relayToken
    }

    /** Whether enough is configured to attempt a wake, by either path. */
    val canWake: Boolean
        get() = wakeBridgeUrl.isNotBlank() ||
            (wakeMac.isNotBlank() && wakeBroadcast.isNotBlank())

    /** Whether enough is configured to open a session. */
    val canOpenSession: Boolean
        get() = hostUrl.isNotBlank()

    // Internal rather than private: the on-device tests clear and seed these
    // keys, and they run in the same module. Reaching them through a copy of the
    // string literals would let a rename break the tests silently, which is the
    // one thing a test must not do.
    internal companion object {
        const val PREFS_NAME = "dsh_mobile"
        const val KEY_HOST_URL = "host_url"
        const val KEY_WAKE_MAC = "wake_mac"
        const val KEY_WAKE_BROADCAST = "wake_broadcast"
        const val KEY_BIOMETRIC = "lock_with_biometric"
        const val KEY_WAKE_BRIDGE_URL = "wake_bridge_url"
        const val KEY_WAKE_BRIDGE_TOKEN = "wake_bridge_token"
    }
}
