package au.com.fuzn.beaver.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.ui.onboarding.OnboardingFlow
import au.com.fuzn.beaver.ui.onboarding.OnboardingMode

/** Opens onboarding again from Settings ("Re-run setup"). Your data is kept. */
@Composable
fun OnboardingLauncher(env: AppEnv, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingFlow(env, OnboardingMode.Rerun, onFinished = onFinished, modifier = modifier)
}
