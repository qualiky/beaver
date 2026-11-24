package au.com.fuzn.beaver.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.ui.slots.OnboardingScreen

/**
 * Opens onboarding again from Settings ("Re-run setup"). Your data is kept.
 *
 * It shows the first-launch [OnboardingScreen] until Onboarding offers a rerun-mode entry point. Then change only
 * this body to that call (the iOS equivalent is `OnboardingView(mode: .rerun)`).
 */
@Composable
fun OnboardingLauncher(env: AppEnv, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScreen(env, onFinished = onFinished, modifier = modifier)
}
