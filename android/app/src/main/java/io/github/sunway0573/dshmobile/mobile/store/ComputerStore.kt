package io.github.sunway0573.dshmobile.mobile.store

import io.github.sunway0573.dshmobile.mobile.model.AuthState
import io.github.sunway0573.dshmobile.mobile.model.ComputerRecord
import java.security.MessageDigest

/**
 * The slice of storage this package needs.
 *
 * An interface rather than a `SharedPreferences` so the store is testable on the
 * JVM. The rules below — one-time migration, address changes not changing
 * identity, corrupt data not taking the list down with it — are the part worth
 * testing, and none of them need a device to exercise.
 */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Serialises the computer list.
 *
 * A tab-separated line per record rather than JSON, deliberately: it needs no
 * dependency, it is trivially inspectable in a bug report, and the escaping
 * rules are small enough to test exhaustively. The escaping is not optional —
 * an alias is user-supplied text and will eventually contain a tab or a newline.
 */
internal object ComputerCodec {

    private const val VERSION = "v1"
    private const val FIELD_SEP = '\t'
    private const val LIST_SEP = '\u001f' // unit separator: legal in a field, never typed
    private const val RECORD_SEP = '\n'

    /** Escape a field so separators inside it cannot split a record. */
    internal fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                FIELD_SEP -> append("\\t")
                RECORD_SEP -> append("\\n")
                LIST_SEP -> append("\\u")
                else -> append(ch)
            }
        }
    }

    /** Inverse of [escape]. Unknown escapes are kept verbatim rather than dropped. */
    internal fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\' || i == value.length - 1) { append(ch); i++; continue }
            when (value[i + 1]) {
                '\\' -> { append('\\'); i += 2 }
                't' -> { append(FIELD_SEP); i += 2 }
                'n' -> { append(RECORD_SEP); i += 2 }
                'u' -> { append(LIST_SEP); i += 2 }
                else -> { append(ch); i++ }
            }
        }
    }

    /** @return the encoded document, or [VERSION] alone when there is nothing. */
    fun encode(records: List<ComputerRecord>): String = buildString {
        append(VERSION)
        for (record in records) {
            append(RECORD_SEP)
            append(
                listOf(
                    record.computerId,
                    record.alias,
                    record.identityFingerprint,
                    record.authState.name,
                    record.lastSeenEpochMs?.toString() ?: "",
                    record.endpoints.joinToString(LIST_SEP.toString()),
                ).joinToString(FIELD_SEP.toString()) { escape(it) },
            )
        }
    }

    /**
     * @return the decoded records. Unreadable lines are skipped rather than
     *   fatal: one corrupt entry must not cost the user every other computer, and
     *   a store that throws on read is a store that cannot be repaired from the
     *   UI. A line that cannot identify a computer is not recoverable; a line
     *   with a bad timestamp is.
     */
    fun decode(text: String?): List<ComputerRecord> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.split(RECORD_SEP)
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(FIELD_SEP).map(::unescape)
            if (parts.size < 6) return@mapNotNull null
            val computerId = parts[0].trim()
            if (computerId.isEmpty()) return@mapNotNull null
            ComputerRecord(
                computerId = computerId,
                alias = parts[1],
                identityFingerprint = parts[2],
                endpoints = parts[5].split(LIST_SEP).filter { it.isNotBlank() },
                authState = runCatching { AuthState.valueOf(parts[3]) }.getOrDefault(AuthState.UNKNOWN),
                lastSeenEpochMs = parts[4].toLongOrNull(),
            )
        }
    }
}

/**
 * The phone's index of computers it has paired with.
 *
 * Holds identity, aliases and endpoints — never credentials. The credentials
 * live in the Keystore-backed store and are referenced by [ComputerRecord.computerId];
 * keeping them apart means a bug that dumps this list dumps nothing secret.
 */
internal class ComputerStore(private val kv: KeyValueStore) {

    internal companion object {
        const val KEY_COMPUTERS = "mobile_computers"
        const val KEY_MIGRATED = "mobile_legacy_migrated"

        /**
         * A stable id for the pre-multi-computer configuration.
         *
         * Derived from the authority rather than random so that a repeated
         * migration — a restore, a lost flag — produces the *same* id instead of
         * a second phantom computer for the same machine.
         */
        fun legacyComputerId(authority: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(authority.lowercase().toByteArray())
            return "legacy-" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }

    fun list(): List<ComputerRecord> = ComputerCodec.decode(kv.getString(KEY_COMPUTERS))

    fun get(computerId: String): ComputerRecord? =
        list().firstOrNull { it.computerId == computerId }

    /**
     * Insert or replace by [ComputerRecord.computerId].
     *
     * Changing the alias, the endpoints or the auth state of an existing
     * computer updates that computer. It never creates a second one — the whole
     * point of a stable id is that a machine which changes networks stays the
     * same machine.
     */
    fun upsert(record: ComputerRecord) {
        val existing = list()
        val index = existing.indexOfFirst { it.computerId == record.computerId }
        val next = if (index >= 0) {
            existing.toMutableList().also { it[index] = record }
        } else {
            existing + record
        }
        kv.putString(KEY_COMPUTERS, ComputerCodec.encode(next))
    }

    fun remove(computerId: String) {
        val next = list().filterNot { it.computerId == computerId }
        kv.putString(KEY_COMPUTERS, ComputerCodec.encode(next))
    }

    fun updateAuthState(computerId: String, state: AuthState) {
        val record = get(computerId) ?: return
        upsert(record.copy(authState = state))
    }

    fun markSeen(computerId: String, epochMs: Long) {
        val record = get(computerId) ?: return
        upsert(record.copy(lastSeenEpochMs = epochMs))
    }

    /**
     * Turn the single-host configuration into the first computer, once.
     *
     * Runs at most once per install, guarded by its own flag rather than by
     * "the list is empty" — a user who removes their only computer must not have
     * it silently reappear from a stale setting on the next launch.
     *
     * @param legacyHostUrl the old `host_url` setting, if any.
     * @param alias how to name it; the host is all the old data knew.
     * @return true when a record was created.
     */
    fun migrateLegacyHost(legacyHostUrl: String?, alias: String): Boolean {
        if (kv.getString(KEY_MIGRATED) == "1") return false
        // Mark first, so a failure below cannot leave the migration half-done and
        // retried into a duplicate on every launch.
        kv.putString(KEY_MIGRATED, "1")

        val url = legacyHostUrl?.trim().orEmpty()
        if (url.isEmpty()) return false

        val authority = url.substringAfter("://").substringBefore('/').ifBlank { url }
        val record = ComputerRecord(
            computerId = legacyComputerId(authority),
            alias = alias.ifBlank { authority },
            // The old configuration had no identity key to pin. Recording that
            // honestly as empty is better than inventing a fingerprint that
            // later code would treat as verified.
            identityFingerprint = "",
            endpoints = listOf(url),
            authState = AuthState.UNKNOWN,
            lastSeenEpochMs = null,
        )
        upsert(record)
        return true
    }

    /** Whether the one-time migration has already run. */
    fun hasMigrated(): Boolean = kv.getString(KEY_MIGRATED) == "1"
}
