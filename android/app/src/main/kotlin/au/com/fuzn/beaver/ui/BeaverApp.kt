package au.com.fuzn.beaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import au.com.fuzn.beaver.app.AppContainer
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.ui.common.StartupErrorBanner
import au.com.fuzn.beaver.ui.plan.PlanScreen
import au.com.fuzn.beaver.ui.slots.MoneyTab
import au.com.fuzn.beaver.ui.slots.OnboardingScreen
import au.com.fuzn.beaver.ui.slots.RosterTab
import au.com.fuzn.beaver.ui.slots.SettingsScreen
import au.com.fuzn.beaver.ui.week.WeekScreen
import kotlinx.coroutines.launch

/** The app environment for any composable below [BeaverRoot] that prefers a local to a parameter. */
val LocalAppEnv = compositionLocalOf<AppEnv> { error("No AppEnv: BeaverRoot provides it") }

enum class Tab(val label: String, val icon: ImageVector) {
    Week("Week", Icons.Filled.CalendarMonth),
    Roster("Roster", Icons.Filled.Groups),
    Money("Money", Icons.Filled.AccountBalanceWallet),
    Plan("Plan", Icons.Filled.Checklist),
}

/**
 * The root of the UI. Waits for the data layer to be ready (a few milliseconds; the window background shows
 * meanwhile), then draws the app under the startup-error banner when the database fell back to memory.
 */
@Composable
fun BeaverRoot(container: AppContainer) {
    val env: State<AppEnv?> = produceState(container.current) { value = container.env.await() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        env.value?.let { ready -> AppWithBanner(ready) }
    }
}

@Composable
fun AppWithBanner(env: AppEnv) {
    CompositionLocalProvider(LocalAppEnv provides env) {
        Column(Modifier.fillMaxSize()) {
            val error = env.startupError
            if (error != null) {
                // Non-dismissible, above everything (onboarding included).
                StartupErrorBanner(error, Modifier.windowInsetsPadding(WindowInsets.statusBars))
            }
            Column(Modifier.weight(1f).then(if (error != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                BeaverApp(env)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaverApp(env: AppEnv) {
    val settings by env.store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(Tab.Week) }

    if (!settings.onboardingDone) {
        OnboardingScreen(env, onFinished = { scope.launch { env.repository.setOnboardingDone(true) } })
        return
    }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(env, onClose = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected.label) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (selected) {
            // "Back up now" opens Settings, where the export lives.
            Tab.Week -> WeekScreen(env, onBackUp = { showSettings = true }, modifier = content)
            Tab.Roster -> RosterTab(env, content)
            Tab.Money -> MoneyTab(env, content)
            Tab.Plan -> PlanScreen(env, content)
        }
    }
}
