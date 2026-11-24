package au.com.fuzn.beaver.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.ui.editors.BillEditor
import au.com.fuzn.beaver.ui.editors.BillPreset
import au.com.fuzn.beaver.ui.editors.GoalEditor
import au.com.fuzn.beaver.ui.editors.IncomeSourceEditor
import au.com.fuzn.beaver.ui.editors.LimitsEditor
import au.com.fuzn.beaver.ui.editors.TemplatesEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The onboarding flow (SPEC 4.0): seven steps with a progress header and a Back/Skip/Next bar.
 *
 * - First run: `OnboardingFlow(env, OnboardingMode.FirstRun, onFinished)`; finishing saves `onboardingDone = true`
 *   and calls [onFinished].
 * - Re-run from Settings: `OnboardingFlow(env, OnboardingMode.Rerun, onFinished = { /* close */ })`. It shows the
 *   existing data, never changes `onboardingDone`, and [onFinished] is called on Done and on the close button, so
 *   the caller just dismisses. The system back button steps back, and closes from the first step.
 *
 * The step, typed fields and the open editor survive rotation and process death.
 */
@Composable
fun OnboardingFlow(
    env: AppEnv,
    mode: OnboardingMode,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = rememberSaveable(
        mode,
        saver = OnboardingModel.saver { OnboardingModel(env.repository, env.backup, mode) },
    ) { OnboardingModel(env.repository, env.backup, mode) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // Picked file, kept as text so a rotation keeps the confirmation dialog.
    var pendingRestoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUri = uri.toString()
    }

    LaunchedEffect(model) { model.load() }
    LaunchedEffect(model.isFinished) { if (model.isFinished) onFinished() }

    fun launchAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun closeSheet() {
        model.sheet = null
        scope.launch { model.refresh() }
    }

    val sheet = model.sheet
    if (sheet != null && model.loaded) {
        BackHandler { closeSheet() }
        OnboardingSheetContent(env, model, sheet, ::closeSheet, modifier)
        return
    }

    BackHandler(enabled = !model.isFirst || mode == OnboardingMode.Rerun) {
        if (model.isFirst) onFinished() else launchAction { model.back() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = { ProgressHeader(model, onClose = onFinished) },
        // The bar is a real sibling below the step, so scrolled content always ends above it.
        bottomBar = {
            NavigationBar(
                model = model,
                busy = busy,
                onBack = { launchAction { model.back() } },
                onSkip = { launchAction { model.skip() } },
                onNext = { launchAction { if (model.isLast) model.finish() else model.next() } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = true)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            StepContent(
                model = model,
                onCurrency = { code -> scope.launch { model.setCurrency(code) } },
                onTimeZone = { id -> scope.launch { model.setTimeZone(id) } },
                onRestore = { picker.launch(RESTORE_MIME_TYPES) },
                onDeleteSource = { s -> scope.launch { model.deleteSource(s) } },
                onDeleteBill = { b -> scope.launch { model.deleteBill(b) } },
            )
        }
    }

    // Restore confirmation, in the app (not a system dialog).
    val uri = pendingRestoreUri
    if (uri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Replace everything with this backup?") },
            text = { Text("Anything already in Beaver on this phone is replaced by the backup, and setup is skipped.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    launchAction { restoreFrom(context, uri, model) }
                }) { Text("Replace and restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") } },
        )
    }
    model.restoreError?.let { message ->
        AlertDialog(
            onDismissRequest = { model.restoreError = null },
            title = { Text("Couldn't restore") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.restoreError = null }) { Text("OK") } },
        )
    }
    model.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { model.errorMessage = null },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.errorMessage = null }) { Text("OK") } },
        )
    }
    model.usualWeekPrompt?.let { sourceId ->
        AlertDialog(
            onDismissRequest = { model.usualWeekPrompt = null },
            title = { Text("Set your usual week (optional)") },
            text = { Text("Add the shifts you normally work, so Beaver can forecast your pay before you roster them.") },
            confirmButton = {
                TextButton(onClick = {
                    model.usualWeekPrompt = null
                    model.sheet = OnboardingSheet.UsualWeek(sourceId)
                }) { Text("Set usual week") }
            },
            dismissButton = { TextButton(onClick = { model.usualWeekPrompt = null }) { Text("Not now") } },
        )
    }
}

/** What the file picker offers: Android often labels a JSON file as plain text or a generic binary. */
private val RESTORE_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/** Reads the picked file off the main thread and hands it to the model. A read error shows like a bad file. */
private suspend fun restoreFrom(context: Context, uri: String, model: OnboardingModel) {
    val text = try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw java.io.IOException("The file could not be opened.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        model.restoreError = e.message ?: "The file could not be read."
        return
    }
    model.restore(text)
}

@Composable
private fun StepContent(
    model: OnboardingModel,
    onCurrency: (String) -> Unit,
    onTimeZone: (String) -> Unit,
    onRestore: () -> Unit,
    onDeleteSource: (au.com.fuzn.beaver.core.IncomeSource) -> Unit,
    onDeleteBill: (au.com.fuzn.beaver.core.Bill) -> Unit,
) {
    val present: (OnboardingSheet) -> Unit = { model.sheet = it }
    when (model.step) {
        OnboardingStep.Welcome -> WelcomeStep(model, onCurrency, onTimeZone, onRestore)
        OnboardingStep.Income -> IncomeStep(model, present, onDeleteSource)
        OnboardingStep.Bills -> BillsStep(model, present, onDeleteBill)
        OnboardingStep.Living -> LivingStep(model)
        OnboardingStep.Goal -> GoalStep(model, present)
        OnboardingStep.Limits -> LimitsStep(model, present)
        OnboardingStep.Summary -> SummaryStep(model)
    }
}

@Composable
private fun ProgressHeader(model: OnboardingModel, onClose: () -> Unit) {
    val label = "Step ${model.stepNumber} of ${model.stepCount}, ${model.step.title}"
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.mode == OnboardingMode.Rerun) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close setup") }
            }
            Row(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = label },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Step ${model.stepNumber} of ${model.stepCount}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(model.step.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(
            progress = { model.progress },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(model.progress, 0f..1f)
            },
        )
    }
}

/** Back, Skip and Next. Bottom insets are applied here, so the bar clears the system navigation and the keyboard. */
@Composable
private fun NavigationBar(
    model: OnboardingModel,
    busy: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack, enabled = !model.isFirst && !busy) { Text("Back") }
                if (model.canSkip) {
                    TextButton(
                        onClick = onSkip,
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Skip. Skips this step. You can set it up later." },
                    ) { Text("Skip") }
                }
                Button(onClick = onNext, enabled = model.canAdvance && !busy, modifier = Modifier.weight(1f)) {
                    Text(
                        if (model.isLast) (if (model.mode == OnboardingMode.Rerun) "Done" else "Start using Beaver") else "Next",
                    )
                }
            }
        }
    }
}

/** The editor chosen in [sheet], drawn full screen over the flow. Closing it reloads what it wrote. */
@Composable
private fun OnboardingSheetContent(
    env: AppEnv,
    model: OnboardingModel,
    sheet: OnboardingSheet,
    close: () -> Unit,
    modifier: Modifier,
) {
    val repository = env.repository
    val currency = model.currency
    when (sheet) {
        is OnboardingSheet.NewIncome -> IncomeSourceEditor(
            repository = repository,
            kind = sheet.kind,
            currencyCode = currency,
            onSaved = { saved ->
                if (saved.kind == IncomeKind.hourly) model.usualWeekPrompt = saved.id
                close()
            },
            onDismiss = close,
            modifier = modifier,
        )
        is OnboardingSheet.EditIncome -> {
            val source = model.sources.firstOrNull { it.id == sheet.id }
            if (source == null) LaunchedEffect(sheet) { close() } else IncomeSourceEditor(
                repository = repository,
                source = source,
                currencyCode = currency,
                onSaved = { close() },
                onDismiss = close,
                onDeleted = close,
                modifier = modifier,
            )
        }
        is OnboardingSheet.UsualWeek -> TemplatesEditor(repository, sheet.sourceId, onDismiss = close, modifier = modifier)
        is OnboardingSheet.NewBill -> BillEditor(
            repository = repository,
            preset = sheet.presetName?.let { name -> BillPreset.common.firstOrNull { it.name == name } },
            currencyCode = currency,
            onSaved = { close() },
            onDismiss = close,
            modifier = modifier,
        )
        is OnboardingSheet.EditBill -> {
            val bill = model.bills.firstOrNull { it.id == sheet.id }
            if (bill == null) LaunchedEffect(sheet) { close() } else BillEditor(
                repository = repository,
                bill = bill,
                currencyCode = currency,
                onSaved = { close() },
                onDismiss = close,
                onDeleted = close,
                modifier = modifier,
            )
        }
        is OnboardingSheet.EditGoal -> {
            val goal = sheet.id?.let { id -> model.goals.firstOrNull { it.id == id } }
            if (sheet.id != null && goal == null) LaunchedEffect(sheet) { close() } else GoalEditor(
                repository = repository,
                goal = goal,
                currencyCode = currency,
                onSaved = { close() },
                onDismiss = close,
                onDeleted = if (goal != null) close else null,
                modifier = modifier,
            )
        }
        OnboardingSheet.Limits -> LimitsEditor(repository, onSaved = { close() }, onDismiss = close, modifier = modifier)
    }
}
