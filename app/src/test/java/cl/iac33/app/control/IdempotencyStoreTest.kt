package cl.iac33.app.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotencyStoreTest {
    @Test
    fun duplicate_keys_are_rejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = IdempotencyStore(context)
        val key = "test-${System.nanoTime()}"
        assertTrue(store.record(key))
        assertTrue(store.seen(key))
        assertFalse(store.record(key))
        store.clear(key)
    }
}
