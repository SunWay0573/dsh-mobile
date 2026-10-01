package io.github.sunway0573.dshmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smallest possible on-device test: proves the runner starts at all.
 *
 * It exists to separate two very different failures. If this passes and the
 * Compose tests hang, the problem is launching an Activity from instrumentation
 * on this device — MIUI is known to block that — and the UI tests need a
 * different shape. If this hangs too, nothing about the UI is involved and the
 * runner or the device is the problem.
 *
 * Without a test like this, "the UI tests hang" is consistent with both, and
 * guessing between them wastes an afternoon.
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentedTest {

    @Test
    fun the_runner_starts() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun the_app_context_is_available() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        assertEquals("io.github.sunway0573.dshmobile", context.packageName)
    }
}
