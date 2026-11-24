package au.com.fuzn.beaver.app

import android.app.Application

/**
 * The process-wide owner of the data layer. [container] is created on first use (by `MainActivity`), not in
 * `onCreate`, so tests that only need a Context do not open the real database.
 */
class BeaverApplication : Application() {
    val container: AppContainer by lazy {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "dev"
        AppContainer(this, appVersion = version)
    }
}
