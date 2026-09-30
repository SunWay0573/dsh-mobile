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

    /** Whether enough is configured to attempt a wake. */
    val canWake: Boolean
        get() = wakeMac.isNotBlank() && wakeBroadcast.isNotBlank()

    /** Whether enough is configured to open a session. */
    val canOpenSession: Boolean
        get() = hostUrl.isNotBlank()

    private companion object {
        const val PREFS_NAME = "dsh_mobile"
        const val KEY_HOST_URL = "host_url"
        const val KEY_WAKE_MAC = "wake_mac"
        const val KEY_WAKE_BROADCAST = "wake_broadcast"
    }
}
