package au.com.fuzn.beaver.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import au.com.fuzn.beaver.app.AppEnv
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.reminders.PayReminderScheduler
import au.com.fuzn.beaver.reminders.ReminderPrefs
import au.com.fuzn.beaver.reminders.notificationsAllowed
import au.com.fuzn.beaver.ui.common.TimeZonePicker
import au.com.fuzn.beaver.ui.editors.BillEditor
import au.com.fuzn.beaver.ui.editors.GoalEditor
import au.com.fuzn.beaver.ui.editors.IncomeSourceEditor
import au.com.fuzn.beaver.ui.editors.LimitsEditor
import au.com.fuzn.beaver.ui.editors.RateCardEditor
import au.com.fuzn.beaver.ui.editors.TemplatesEditor
import kotlinx.coroutines.launch
import java.time.Instant

/** A part of the Settings list the screen can open scrolled to. */
enum class SettingsSection { Data }

/**
 * Settings: a full screen with its own top bar. Sub-screens (editors, the time zone list, re-run setup) replace the
 * list in place and the system back button steps back to it. Where it is showing survives rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHost(
    env: AppEnv,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialSection: SettingsSection? = null,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val settings by env.store.settings.collectAsState()
    val input by env.store.input.collectAsState()

    var routeText by rememberSaveable { mutableStateOf(SettingsRoute.List.encode()) }
    val route = remember(routeText) { SettingsRoute.parse(routeText) }
    fun go(to: SettingsRoute) {
        routeText = to.encode()
    }
    BackHandler(enabled = route != SettingsRoute.List) { go(SettingsRoute.List) }

    val reader: suspend (String) -> String = { SafFiles.read(resolver, Uri.parse(it)) }
    val state = rememberSaveable(saver = SettingsState.saver(env.repository, env.backup, reader)) {
        SettingsState(env.repository, env.backup, reader)
    }

    // The tables screen needs the built-in table too, which the engine input leaves out.
    var tables by remember { mutableStateOf<List<WithholdingTable>?>(null) }
    LaunchedEffect(input.withholdingTables) { tables = env.repository.withholdingTables() }

    // region Reminder toggle
    val prefs = remember { ReminderPrefs.of(context) }
    val scheduler = remember { PayReminderScheduler.of(context) }
    var reminderOn by rememberSaveable { mutableStateOf(prefs.enabled) }
    var reminderMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationDenied = "Notifications are off for Beaver. Allow them in Android settings to use this."
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scheduler.enable(env.store.forecast.value, env.store.settings.value)
            reminderOn = true
        } else {
            prefs.enabled = false
            reminderOn = false
            reminderMessage = notificationDenied
        }
    }
    val setReminder: (Boolean) -> Unit = { on ->
        reminderMessage = null
        if (!on) {
            scheduler.disable()
            reminderOn = false
        } else if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context)) {
            // Asked only now, when the toggle is turned on.
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scheduler.enable(env.store.forecast.value, env.store.settings.value)
            reminderOn = true
        }
    }
    // endregion

    // region File pickers
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch { state.export(settings.lastExportAt) { SafFiles.write(resolver, uri, it) } }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    state.stageImport(SafFiles.displayName(resolver, uri), uri.toString(), SafFiles.read(resolver, uri))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.importFailedToRead(e)
                }
            }
        }
    }
    // endregion

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
    val allTables = tables

    when (route) {
        SettingsRoute.List -> Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text("Settings", Modifier.semantics { heading() }) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close settings") }
                    },
                )
            },
        ) { inner ->
            Column(Modifier.padding(inner).verticalScroll(rememberScrollState())) {
                SettingsListContent(
                    scrollTo = initialSection,
                    data = SettingsListData(
                        input = input,
                        settings = settings,
                        tables = allTables ?: emptyList(),
                        version = version,
                        reminderOn = reminderOn,
                        reminderMessage = reminderMessage,
                        lastExportedText = SettingsState.lastExportedText(settings),
                    ),
                    actions = SettingsListActions(
                        open = ::go,
                        setCurrency = { scope.launch { state.setCurrency(it) } },
                        saveBudget = { scope.launch { state.setLivingBudget(it) } },
                        export = { exporter.launch(SettingsState.exportFilename(Instant.now(), settings.timeZone)) },
                        import = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        loadDemo = state::requestLoadDemo,
                        erase = state::requestErase,
                        setReminder = setReminder,
                    ),
                )
            }
        }

        is SettingsRoute.Source, SettingsRoute.SourceNew -> {
            val source = (route as? SettingsRoute.Source)?.let { r -> input.sources.firstOrNull { it.id == r.id } }
            if (route is SettingsRoute.Source && source == null) {
                LaunchedEffect(route) { go(SettingsRoute.List) }
            } else {
                IncomeSourceEditor(
                    env.repository, onSaved = { go(SettingsRoute.List) }, onDismiss = { go(SettingsRoute.List) },
                    modifier = modifier, source = source, currencyCode = settings.currency,
                    onDeleted = { go(SettingsRoute.List) },
                    onEditTemplates = { go(SettingsRoute.Templates(it)) },
                )
            }
        }

        is SettingsRoute.Templates ->
            TemplatesEditor(env.repository, route.sourceId, onDismiss = { go(SettingsRoute.List) }, modifier = modifier)

        is SettingsRoute.Card, SettingsRoute.CardNew -> {
            val card = (route as? SettingsRoute.Card)?.let { r -> input.rateCards.firstOrNull { it.id == r.id } }
            if (route is SettingsRoute.Card && card == null) {
                LaunchedEffect(route) { go(SettingsRoute.List) }
            } else {
                RateCardEditor(
                    env.repository, onSaved = { go(SettingsRoute.List) }, onDismiss = { go(SettingsRoute.List) },
                    modifier = modifier, card = card, currencyCode = settings.currency,
                )
            }
        }

        is SettingsRoute.Table -> {
            val table = allTables?.firstOrNull { it.id == route.id }
            when {
                allTables == null -> Unit
                table == null -> LaunchedEffect(route) { go(SettingsRoute.List) }
                SettingsState.isEditable(table) -> WithholdingTableEditor(
                    table,
                    onSave = { edited -> scope.launch { if (state.saveTable(edited) != null) go(SettingsRoute.List) } },
                    onBack = { go(SettingsRoute.List) },
                    modifier = modifier,
                )
                else -> BuiltinWithholdingView(
                    table,
                    onDuplicate = { scope.launch { state.duplicate(table); go(SettingsRoute.List) } },
                    onBack = { go(SettingsRoute.List) },
                    modifier = modifier,
                )
            }
        }

        is SettingsRoute.Bill, SettingsRoute.BillNew -> {
            val bill = (route as? SettingsRoute.Bill)?.let { r -> input.bills.firstOrNull { it.id == r.id } }
            if (route is SettingsRoute.Bill && bill == null) {
                LaunchedEffect(route) { go(SettingsRoute.List) }
            } else {
                BillEditor(
                    env.repository, onSaved = { go(SettingsRoute.List) }, onDismiss = { go(SettingsRoute.List) },
                    modifier = modifier, bill = bill, currencyCode = settings.currency,
                    onDeleted = { go(SettingsRoute.List) },
                )
            }
        }

        is SettingsRoute.Goal, SettingsRoute.GoalNew -> {
            val goal = (route as? SettingsRoute.Goal)?.let { r -> input.goals.firstOrNull { it.id == r.id } }
            if (route is SettingsRoute.Goal && goal == null) {
                LaunchedEffect(route) { go(SettingsRoute.List) }
            } else {
                GoalEditor(
                    env.repository, onSaved = { go(SettingsRoute.List) }, onDismiss = { go(SettingsRoute.List) },
                    modifier = modifier, goal = goal, currencyCode = settings.currency,
                    onDeleted = { go(SettingsRoute.List) },
                )
            }
        }

        SettingsRoute.Limits ->
            LimitsEditor(env.repository, onSaved = { go(SettingsRoute.List) }, onDismiss = { go(SettingsRoute.List) }, modifier = modifier)

        SettingsRoute.TimeZone -> TimeZonePicker(
            selectedId = settings.timeZoneId,
            onSelect = { id ->
                scope.launch { state.setTimeZone(id) }
                go(SettingsRoute.List)
            },
            onBack = { go(SettingsRoute.List) },
            modifier = modifier,
        )

        SettingsRoute.Setup -> OnboardingLauncher(env, onFinished = { go(SettingsRoute.List) }, modifier = modifier)
    }

    SettingsDialogs(state, scope = { block -> scope.launch { block() } })
}

/** The confirmation, error and "done" dialogs. */
@Composable
private fun SettingsDialogs(state: SettingsState, scope: (suspend () -> Unit) -> Unit) {
    state.pending?.let { action ->
        val (title, button, message) = when (action) {
            PendingAction.Import -> Triple(
                "Replace everything?", "Replace with backup",
                "Importing ${state.importName ?: "this file"} replaces everything in Beaver with the contents of the file. This cannot be undone.",
            )
            PendingAction.LoadDemo -> Triple(
                "Load demo data?", "Replace with demo",
                "This replaces everything in Beaver with a fictional example (\"Sam\"). Your own data will be lost unless you have a backup.",
            )
            PendingAction.Erase -> Triple(
                "Erase all data?", "Erase everything",
                "This deletes all your data and returns Beaver to first-time setup. This cannot be undone.",
            )
        }
        AlertDialog(
            onDismissRequest = state::cancelPending,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { scope { state.confirmPending() } }) {
                    Text(button, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = state::cancelPending) { Text("Cancel") } },
        )
    }
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { state.errorMessage = null },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { state.errorMessage = null }) { Text("OK") } },
        )
    }
    state.statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { state.statusMessage = null },
            title = { Text("Done") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { state.statusMessage = null }) { Text("OK") } },
        )
    }
}
