package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.model.AuthState
import io.github.sunway0573.dshmobile.mobile.store.ComputerStore
import io.github.sunway0573.dshmobile.mobile.store.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * Upgrading from the single-host version, where the app stored one `host_url`
 * and nothing else.
 *
 * Two failure modes matter and are opposite. Migrating twice gives the user a
 * phantom second copy of a computer they own one of. Not migrating loses their
 * only configuration and looks like the app forgot them.
 */
class ComputerMigrationTest {

    private val legacyUrl = "http://192.168.10.3:3080/?token=abc"

    @Test
    fun a_legacy_address_becomes_the_first_computer() {
        val store = ComputerStore(MemStore())
        assertTrue(store.migrateLegacyHost(legacyUrl, alias = "我的 Mac"))

        val records = store.list()
        assertEquals(1, records.size)
        assertEquals("我的 Mac", records[0].alias)
        assertEquals(listOf(legacyUrl), records[0].endpoints)
    }

    // The old configuration had no identity key, so there is nothing to pin. An
    // empty fingerprint says "not verified", which later code must treat as
    // unverified — inventing one would be a lie the reconnect path would believe.
    @Test
    fun the_migrated_computer_has_no_invented_fingerprint() {
        val store = ComputerStore(MemStore())
        store.migrateLegacyHost(legacyUrl, alias = "我的 Mac")
        assertEquals("", store.list()[0].identityFingerprint)
    }

    @Test
    fun the_migrated_computer_is_not_assumed_reachable() {
        val store = ComputerStore(MemStore())
        store.migrateLegacyHost(legacyUrl, alias = "我的 Mac")
        assertEquals(AuthState.UNKNOWN, store.list()[0].authState)
        assertNull(store.list()[0].lastSeenEpochMs)
    }

    // Migration runs once, and is guarded by its own flag rather than by "the
    // list is empty".
    @Test
    fun it_does_not_run_twice() {
        val store = ComputerStore(MemStore())
        assertTrue(store.migrateLegacyHost(legacyUrl, alias = "我的 Mac"))
        assertFalse(store.migrateLegacyHost(legacyUrl, alias = "我的 Mac"))
        assertEquals(1, store.list().size)
    }

    // The reason the flag exists rather than an emptiness check: a user who
    // deliberately removes their only computer must not have it reappear from a
    // stale setting on the next launch.
    @Test
    fun a_removed_computer_does_not_come_back() {
        val store = ComputerStore(MemStore())
        store.migrateLegacyHost(legacyUrl, alias = "我的 Mac")
        val id = store.list()[0].computerId
        store.remove(id)
        assertTrue(store.list().isEmpty())

        assertFalse("a second migration must not resurrect it", store.migrateLegacyHost(legacyUrl, "我的 Mac"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun nothing_to_migrate_marks_the_migration_done() {
        val store = ComputerStore(MemStore())
        assertFalse(store.migrateLegacyHost(null, alias = "x"))
        assertFalse(store.migrateLegacyHost("   ", alias = "x"))
        assertTrue("the attempt is recorded so it is not retried forever", store.hasMigrated())
        assertTrue(store.list().isEmpty())
    }

    // A repeat migration — a restore, a lost flag — must produce the same id, or
    // the same machine ends up as two computers with two sets of authorisations.
    @Test
    fun the_legacy_id_is_derived_not_random() {
        val first = ComputerStore(MemStore())
        first.migrateLegacyHost(legacyUrl, alias = "我的 Mac")

        val second = ComputerStore(MemStore())
        second.migrateLegacyHost(legacyUrl, alias = "我的 Mac")

        assertEquals(
            "the same address must always produce the same legacy id",
            first.list()[0].computerId,
            second.list()[0].computerId,
        )
    }

    @Test
    fun the_legacy_id_ignores_case_in_the_authority() {
        assertEquals(
            ComputerStore.legacyComputerId("My-Mac.Local:3080"),
            ComputerStore.legacyComputerId("my-mac.local:3080"),
        )
    }

    @Test
    fun different_addresses_produce_different_legacy_ids() {
        assertTrue(
            ComputerStore.legacyComputerId("mac.local") !=
                ComputerStore.legacyComputerId("mini.local"),
        )
    }

    @Test
    fun the_alias_falls_back_to_the_authority_when_blank() {
        val store = ComputerStore(MemStore())
        store.migrateLegacyHost(legacyUrl, alias = "   ")
        assertEquals("192.168.10.3:3080", store.list()[0].alias)
    }

    // An existing list must survive a migration attempt that is somehow reached
    // again, rather than being replaced by the legacy record.
    @Test
    fun migration_does_not_disturb_records_that_are_already_there() {
        val kv = MemStore()
        val store = ComputerStore(kv)
        store.upsert(
            io.github.sunway0573.dshmobile.mobile.model.ComputerRecord(
                computerId = "pc-real",
                alias = "真的电脑",
                identityFingerprint = "fp",
                endpoints = listOf("https://real"),
                authState = AuthState.AUTHORIZED,
                lastSeenEpochMs = 1L,
            ),
        )
        // Simulate a lost flag by clearing it directly.
        kv.remove(ComputerStore.KEY_MIGRATED)
        assertTrue(store.migrateLegacyHost(legacyUrl, alias = "旧的"))

        val ids = store.list().map { it.computerId }.toSet()
        assertEquals(2, ids.size)
        assertNotNull(store.get("pc-real"))
        assertEquals(AuthState.AUTHORIZED, store.get("pc-real")?.authState)
    }
}
