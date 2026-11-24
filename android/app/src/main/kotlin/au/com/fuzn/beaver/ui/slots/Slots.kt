package au.com.fuzn.beaver.ui.slots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.ui.onboarding.OnboardingFlow
import au.com.fuzn.beaver.ui.onboarding.OnboardingMode

// HOOK POINTS for the screens other engineers are building. Each is a placeholder; replace its body with a call to
// your screen (for example `RosterScreen(env, modifier)`). Keep the signature: BeaverApp calls these.
//
// Each screen lives in its own package under ui/ and reads everything from `env`:
//   env.store      live settings / input / forecast (StateFlows)
//   env.repository all writes
//   env.backup     export and import
// Tab content is drawn inside the shell's Scaffold, which already supplies the top bar and bottom bar; do not add
// your own top app bar to a tab. Settings and onboarding are full screens and bring their own.

/** Roster tab (BEAVER-25 to 28 on iOS). */
@Composable
fun RosterTab(env: AppEnv, modifier: Modifier = Modifier) = SlotPlaceholder("Roster", modifier)

/** Money tab: bills, spending and goals. */
@Composable
fun MoneyTab(env: AppEnv, modifier: Modifier = Modifier) = au.com.fuzn.beaver.ui.money.MoneyScreen(env, modifier)

/**
 * Settings, a full screen opened from the gear in the top bar and from the Week "Back up now" button.
 * [onClose] returns to the tabs. The system back button also closes it (handled by the shell).
 */
@Composable
fun SettingsScreen(env: AppEnv, onClose: () -> Unit, modifier: Modifier = Modifier) =
    SlotPlaceholder("Settings", modifier, actionLabel = "Close", onAction = onClose)

/**
 * First-launch onboarding, shown while the persisted `onboardingDone` setting is false. Call [onFinished] when the
 * user finishes or skips; the shell then saves `onboardingDone = true` and shows the tabs.
 */
@Composable
fun OnboardingScreen(env: AppEnv, onFinished: () -> Unit, modifier: Modifier = Modifier) =
    OnboardingFlow(env, OnboardingMode.FirstRun, onFinished, modifier)

@Composable
private fun SlotPlaceholder(
    title: String,
    modifier: Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$title (coming soon)")
        if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}
