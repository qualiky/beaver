package au.com.fuzn.beaver.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.fuzn.beaver.app.AppContainer
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.BeaverDatabase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppContainerTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun healthyStartHasNoErrorAndReadsOnboardingFromTheDatabase() = runBlocking {
        val container = AppContainer(context, openDatabase = BeaverDatabase::inMemory, clock = { TODAY })
        val env = container.env.await()
        assertNull(env.startupError)
        assertFalse(env.store.settings.value.onboardingDone) // a fresh database shows onboarding

        env.repository.setOnboardingDone(true)
        env.store.reload()
        assertTrue(env.store.settings.value.onboardingDone) // the gate follows the persisted setting
        assertNotNull(container.current)
        container.scope.cancel()
    }

    @Test
    fun failedOpenFallsBackToMemoryWithABanner() = runBlocking {
        val container = AppContainer(
            context,
            openDatabase = { throw IllegalStateException("unable to open database file") },
            fallbackDatabase = BeaverDatabase::inMemory,
            clock = { TODAY },
        )
        val env = container.env.await()
        assertEquals("unable to open database file", env.startupError)
        // The app still works, in memory.
        env.repository.setLivingBudget(Money.dollars(120))
        env.store.reload()
        assertEquals(Money.dollars(120), env.store.settings.value.livingBudget)
        container.scope.cancel()
    }

    @Test
    fun anExceptionWithoutAMessageStillGivesADetail() = runBlocking {
        val container = AppContainer(context, openDatabase = { throw RuntimeException() }, fallbackDatabase = BeaverDatabase::inMemory, clock = { TODAY })
        assertEquals("RuntimeException", container.env.await().startupError)
        container.scope.cancel()
    }
}
