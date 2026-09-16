package cl.iac33.app.location

import cl.iac33.app.core.LocationPermission
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPermissionTest {
    @Test fun denied_is_supported() { assertEquals(LocationPermission.DENIED, LocationPermission.valueOf("DENIED")) }
    @Test fun precise_is_supported() { assertEquals(LocationPermission.PRECISE, LocationPermission.valueOf("PRECISE")) }
}
