package au.com.fuzn.beaver.app

import android.content.Context
import au.com.fuzn.beaver.data.AppStore
import au.com.fuzn.beaver.data.Backup
import au.com.fuzn.beaver.data.BeaverDatabase
import au.com.fuzn.beaver.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything a screen needs from the app, handed to it as one value. Screens never open the database themselves.
 *
 * - [store]: live settings, engine input and forecast as `StateFlow`s (read-only for screens).
 * - [repository]: every write (`save`, `markPaid`, `setLivingBudget` ...) goes through it; the store follows.
 * - [backup]: JSON export and import.
 * - [startupError]: non-null when the database could not be opened and the app runs in memory.
 */
class AppEnv(
    val store: AppStore,
    val repository: Repository,
    val backup: Backup,
    val startupError: String?,
)

/**
 * Owns the database, repository and store for the life of the process. Created lazily by [BeaverApplication]
 * and started once; [env] completes when the first state has been read, so the first frame is correct.
 *
 * If the on-disk database cannot be opened it falls back to an in-memory one and records [AppEnv.startupError];
 * the UI shows a non-dismissible banner.
 */
class AppContainer(
    private val context: Context,
    private val appVersion: String = "dev",
    private val openDatabase: (Context) -> BeaverDatabase = BeaverDatabase::open,
    private val fallbackDatabase: (Context) -> BeaverDatabase = BeaverDatabase::inMemory,
    private val clock: (ZoneId) -> LocalDate = { LocalDate.now(it) },
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Volatile
    var current: AppEnv? = null
        private set

    /** Completes with the ready environment. Await it before drawing anything that needs the store. */
    val env: Deferred<AppEnv> = scope.async { build() }

    /** Call when the app returns to the foreground so a new day moves the forecast window. */
    fun refreshToday() {
        current?.store?.refreshToday()
    }

    private suspend fun build(): AppEnv {
        var startupError: String? = null
        val database = try {
            openDatabase(context).also { it.openHelper.writableDatabase } // force the open now, so failure shows here
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            startupError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            fallbackDatabase(context)
        }
        val repository = Repository(database)
        val store = AppStore.create(repository, scope, clock)
        return AppEnv(store, repository, Backup(repository, appVersion), startupError).also { current = it }
    }
}
