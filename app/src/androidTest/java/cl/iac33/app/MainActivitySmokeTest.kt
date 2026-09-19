package cl.iac33.app

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun mainActivityIsDeclaredAndLaunchable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, MainActivity::class.java)
        val info = context.packageManager.getActivityInfo(component, 0)

        assertTrue("MainActivity must be enabled", info.enabled)
        assertTrue("MainActivity must be exported", info.exported)
        assertEquals(context.packageName, component.packageName)
        assertEquals(MainActivity::class.java.name, component.className)
    }
}
