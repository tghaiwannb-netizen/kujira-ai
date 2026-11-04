package app.kujira.android

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end launch smoke test: the real activity, real DI container, real
 * Compose tree. Catches startup crashes that unit tests miss.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "w411dp-h891dp-xxhdpi",
    application = KujiraApplication::class
)
class MainActivityLaunchTest {

    @Test
    fun launchesWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        val activity = controller.get()
        assertFalse("activity died during launch", activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun containerFromRealApplicationIsWired() {
        val app = ApplicationProvider.getApplicationContext<KujiraApplication>()
        assertFalse(app.container === null)
        assertFalse(app.container.settingsStore.settings.value.let { it.activeProviderId == null })
    }
}
