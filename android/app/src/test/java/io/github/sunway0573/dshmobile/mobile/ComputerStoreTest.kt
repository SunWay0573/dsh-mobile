package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.model.AuthState
import io.github.sunway0573.dshmobile.mobile.model.ComputerRecord
import io.github.sunway0573.dshmobile.mobile.store.ComputerCodec
import io.github.sunway0573.dshmobile.mobile.store.ComputerStore
import io.github.sunway0573.dshmobile.mobile.store.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory, so the storage rules can be tested without a device. */
private class FakeStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

private fun record(
    id: String,
    alias: String = id,
    endpoints: List<String> = listOf("https://$id.example"),
    auth: AuthState = AuthState.AUTHORIZED,
) = ComputerRecord(
    computerId = id,
    alias = alias,
    identityFingerprint = "fp-$id",
    endpoints = endpoints,
    authState = auth,
    lastSeenEpochMs = null,
)

class ComputerStoreTest {

    @Test
    fun starts_empty() {
        assertTrue(ComputerStore(FakeStore()).list().isEmpty())
    }

    @Test
    fun upsert_then_get_round_trips() {
        val store = ComputerStore(FakeStore())
        val original = record("pc-1", alias = "我的 Mac")
        store.upsert(original)
        assertEquals(original, store.get("pc-1"))
    }

    // The rule the whole model exists for: a machine that changes its address is
    // the same machine. If an endpoint update produced a new record, every
    // stored target, draft and approval for it would silently orphan.
    @Test
    fun changing_the_address_does_not_create_a_second_computer() {
        val store = ComputerStore(FakeStore())
        store.upsert(record("pc-1", endpoints = listOf("http://192.168.1.5:3080")))
        store.upsert(record("pc-1", endpoints = listOf("https://mac.tailnet.ts.net")))

        assertEquals("still one computer", 1, store.list().size)
        assertEquals(
            listOf("https://mac.tailnet.ts.net"),
            store.get("pc-1")?.endpoints,
        )
    }

    @Test
    fun changing_the_alias_keeps_the_identity() {
        val store = ComputerStore(FakeStore())
        store.upsert(record("pc-1", alias = "旧名字"))
        store.upsert(record("pc-1", alias = "我的 MacBook"))
        assertEquals(1, store.list().size)
        assertEquals("我的 MacBook", store.get("pc-1")?.alias)
    }

    @Test
    fun two_computers_coexist_and_do_not_overwrite_each_other() {
        val store = ComputerStore(FakeStore())
        store.upsert(record("pc-mac", alias = "Mac", endpoints = listOf("https://a")))
        store.upsert(record("pc-mini", alias = "mini", endpoints = listOf("https://b")))
        assertEquals(2, store.list().size)
        assertEquals("Mac", store.get("pc-mac")?.alias)
        assertEquals("mini", store.get("pc-mini")?.alias)
    }

    @Test
    fun remove_only_removes_the_named_computer() {
        val store = ComputerStore(FakeStore())
        store.upsert(record("pc-mac"))
        store.upsert(record("pc-mini"))
        store.remove("pc-mac")
        assertEquals(listOf("pc-mini"), store.list().map { it.computerId })
    }

    @Test
    fun revoking_one_computer_leaves_the_other_alone() {
        val store = ComputerStore(FakeStore())
        store.upsert(record("pc-mac"))
        store.upsert(record("pc-mini"))
        store.updateAuthState("pc-mac", AuthState.REVOKED)
        assertEquals(AuthState.REVOKED, store.get("pc-mac")?.authState)
        assertEquals(AuthState.AUTHORIZED, store.get("pc-mini")?.authState)
    }

    @Test
    fun updating_an_unknown_computer_is_a_no_op_rather_than_a_new_record() {
        val store = ComputerStore(FakeStore())
        store.updateAuthState("nobody", AuthState.REVOKED)
        store.markSeen("nobody", 1_000)
        assertTrue("an unknown id must not conjure a record", store.list().isEmpty())
    }
}

class ComputerCodecTest {

    @Test
    fun empty_round_trips_to_empty() {
        assertTrue(ComputerCodec.decode(ComputerCodec.encode(emptyList())).isEmpty())
        assertTrue(ComputerCodec.decode(null).isEmpty())
        assertTrue(ComputerCodec.decode("").isEmpty())
    }

    @Test
    fun a_record_survives_a_round_trip() {
        val records = listOf(
            record("pc-1", alias = "我的 Mac"),
            record("pc-2", alias = "mini", auth = AuthState.REVOKED),
        )
        assertEquals(records, ComputerCodec.decode(ComputerCodec.encode(records)))
    }

    // Aliases are user-supplied. One of them will eventually contain a tab or a
    // newline, and an unescaped separator turns one record into two broken ones.
    @Test
    fun separators_inside_a_value_do_not_split_the_record() {
        val nasty = record("pc-1", alias = "a\tb\nc\\d")
        val decoded = ComputerCodec.decode(ComputerCodec.encode(listOf(nasty)))
        assertEquals(1, decoded.size)
        assertEquals("a\tb\nc\\d", decoded[0].alias)
    }

    @Test
    fun several_endpoints_survive_in_order() {
        val multi = record("pc-1", endpoints = listOf("https://one", "http://two", "https://three"))
        val decoded = ComputerCodec.decode(ComputerCodec.encode(listOf(multi)))
        assertEquals(listOf("https://one", "http://two", "https://three"), decoded[0].endpoints)
    }

    @Test
    fun a_negative_or_missing_last_seen_decodes_as_null() {
        val never = record("pc-1").copy(lastSeenEpochMs = null)
        assertNull(ComputerCodec.decode(ComputerCodec.encode(listOf(never)))[0].lastSeenEpochMs)

        val seen = record("pc-1").copy(lastSeenEpochMs = 1_790_000_000_000)
        assertEquals(
            1_790_000_000_000,
            ComputerCodec.decode(ComputerCodec.encode(listOf(seen)))[0].lastSeenEpochMs,
        )
    }

    // A store that throws on read is a store the user cannot repair from the UI.
    @Test
    fun corrupt_data_degrades_instead_of_throwing() {
        assertTrue(ComputerCodec.decode("not a document").isEmpty())
        assertTrue(ComputerCodec.decode("v1\nonly\ttwo").isEmpty())
        // One bad line must not cost the other computers.
        val good = ComputerCodec.encode(listOf(record("pc-ok")))
        val mixed = good + "\nv1\nshort\tline\n" + good.lines().last()
        assertEquals(2, ComputerCodec.decode(mixed).size)
    }

    @Test
    fun an_unknown_auth_state_becomes_unknown_rather_than_failing() {
        val encoded = ComputerCodec.encode(listOf(record("pc-1"))).replace("AUTHORIZED", "SOMETHING_NEW")
        val decoded = ComputerCodec.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(AuthState.UNKNOWN, decoded[0].authState)
    }

    @Test
    fun a_future_document_version_is_refused_rather_than_misread() {
        val encoded = ComputerCodec.encode(listOf(record("pc-1"))).replaceFirst("v1", "v9")
        assertTrue(ComputerCodec.decode(encoded).isEmpty())
    }

    @Test
    fun a_record_without_an_id_is_dropped() {
        val encoded = "v1\n\t\t\tAUTHORIZED\t\t"
        assertTrue(ComputerCodec.decode(encoded).isEmpty())
    }

    @Test
    fun primary_endpoint_is_the_first_one() {
        assertEquals("https://one", record("pc", endpoints = listOf("https://one", "http://two")).primaryEndpoint)
        assertNull(record("pc", endpoints = emptyList()).primaryEndpoint)
    }
}
