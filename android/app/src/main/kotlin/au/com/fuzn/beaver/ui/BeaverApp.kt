package au.com.fuzn.beaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    Week("Week", Icons.Filled.CalendarMonth),
    Roster("Roster", Icons.Filled.Groups),
    Money("Money", Icons.Filled.AccountBalanceWallet),
    Plan("Plan", Icons.Filled.Checklist),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaverApp() {
    // Placeholder gate: in-memory only, replaced by a persisted setting later.
    var onboardingDone by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(Tab.Week) }

    if (!onboardingDone) {
        PlaceholderScreen("Onboarding", actionLabel = "Finish setup") { onboardingDone = true }
        return
    }
    if (showSettings) {
        PlaceholderScreen("Settings", actionLabel = "Close") { showSettings = false }
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text("${selected.label} (placeholder)")
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, actionLabel: String, onAction: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$title (placeholder)")
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
