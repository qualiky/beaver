package au.com.fuzn.beaver.reminders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import au.com.fuzn.beaver.app.AppEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Keeps the scheduled payday-eve reminders in step with the forecast: reschedules whenever the forecast or the
 * settings change (a no-op while the reminder is off). Settings composes it while it is open. For reminders to
 * follow edits made on other tabs, the app shell should also call `PayReminderSync(env)` once, high in the tree.
 */
@Composable
fun PayReminderSync(env: AppEnv) {
    val context = LocalContext.current
    val scheduler = remember(context) { PayReminderScheduler.of(context) }
    LaunchedEffect(env, scheduler) {
        combine(env.store.forecast, env.store.settings) { forecast, settings -> forecast to settings }
            .collect { (forecast, settings) ->
                withContext(Dispatchers.Default) { scheduler.reschedule(forecast, settings) }
            }
    }
}
