package cl.iac33.app.memory

import cl.iac33.app.core.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MemoryCodecTest {
    @Test
    fun roundTrip_preservesUnicodeAndSeparators() {
        val item = MemoryItem("id.1", "user|scope", "Memoria de C33: sismo 7.2, Chile")
        val decoded = MemoryCodec.decode(MemoryCodec.encode(item))
        assertNotNull(decoded)
        assertEquals(item, decoded)
    }

    @Test
    fun malformedData_isRejected() {
        assertEquals(null, MemoryCodec.decode("invalid"))
    }
}
