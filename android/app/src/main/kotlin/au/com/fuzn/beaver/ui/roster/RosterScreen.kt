package au.com.fuzn.beaver.ui.roster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.data.Repository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class BulkAction { CopyLastWeek, ApplyTemplate, SaveTemplate }

/**
 * The Roster tab: a Monday to Sunday week of shifts with totals, warnings and bulk actions.
 * It owns its [RosterState] and keeps it in step with the database, so edits made elsewhere (Settings, other tabs) show up.
 *
 * @param onOpenSettings called from the "no hourly sources" empty state.
 * @param initialWeek overrides the week shown first (tests and previews). Null means the current week.
 */
@Composable
fun RosterScreen(
    repository: Repository,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    initialWeek: LocalDate? = null,
) {
    val state = remember(repository) { RosterState(repository, LocalDate.now(), initialWeek) }
    LaunchedEffect(state) {
        repository.observeSnapshot { LocalDate.now(it) }.collect { snapshot ->
            state.currency = snapshot.settings.currency
            state.reload(today = snapshot.input.today)
        }
    }
    RosterHost(state, onOpenSettings, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RosterHost(state: RosterState, onOpenSettings: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<ShiftEditorState?>(null) }
    var logPay by remember { mutableStateOf<LogPayState?>(null) }
    var pending by remember { mutableStateOf<BulkAction?>(null) }

    // Messages go to the Snackbar. Delete shows an Undo action for 6 seconds.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        if (message.undoable) {
            val timeout = launch {
                delay(UNDO_MILLIS)
                snackbarHost.currentSnackbarData?.dismiss()
            }
            val result = snackbarHost.showSnackbar(
                message.text, actionLabel = "Undo", withDismissAction = false, duration = SnackbarDuration.Indefinite,
            )
            timeout.cancel()
            if (result == SnackbarResult.ActionPerformed) state.undoDelete() else state.dismissMessage(message.serial)
        } else {
            snackbarHost.showSnackbar(message.text, duration = SnackbarDuration.Long)
            state.dismissMessage(message.serial)
        }
    }

    val data = state.data
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (state.loaded && data.hourlySources.isNotEmpty() && data.weekShifts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { editor = state.makeEditor() } },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add shift") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RosterContent(
                data = data,
                currency = state.currency,
                loaded = state.loaded,
                onPreviousWeek = { scope.launch { state.goToPreviousWeek() } },
                onNextWeek = { scope.launch { state.goToNextWeek() } },
                onThisWeek = { scope.launch { state.goToThisWeek() } },
                onAddShift = { scope.launch { editor = state.makeEditor() } },
                onEditShift = { shift -> scope.launch { editor = state.makeEditor(shift) } },
                onDeleteShift = { shift -> scope.launch { state.delete(shift) } },
                onMarkWorked = { shift -> scope.launch { state.setStatus(ShiftStatus.worked, shift) } },
                onCopyLastWeek = { pending = BulkAction.CopyLastWeek },
                onApplyTemplate = { pending = BulkAction.ApplyTemplate },
                onSaveTemplate = { pending = BulkAction.SaveTemplate },
                onLogPay = { logPay = LogPayState(state.data) },
                onOpenSettings = onOpenSettings,
            )
        }
    }

    pending?.let { action ->
        BulkConfirmDialog(
            action = action,
            data = data,
            onConfirm = {
                pending = null
                scope.launch {
                    when (action) {
                        BulkAction.CopyLastWeek -> state.copyLastWeek()
                        BulkAction.ApplyTemplate -> state.applyTemplate()
                        BulkAction.SaveTemplate -> state.saveTemplateAction()
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }

    editor?.let { current ->
        ShiftEditorSheet(
            editor = current,
            currency = state.currency,
            onDismiss = { editor = null },
            onSave = { shift ->
                scope.launch { if (state.save(shift)) editor = null }
            },
        )
    }

    logPay?.let { current ->
        LogPaySheet(
            state = state,
            log = current,
            onDismiss = { logPay = null },
        )
    }

    state.errorMessage?.let { text ->
        AlertDialog(
            onDismissRequest = state::clearError,
            title = { Text("Something went wrong") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = state::clearError) { Text("OK") } },
        )
    }
}

private const val UNDO_MILLIS = 6_000L

// region Content

/** The whole week view as a stateless composable: everything it needs is in [data] and the callbacks. */
@Composable
internal fun RosterContent(
    data: RosterData,
    currency: String,
    loaded: Boolean,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onThisWeek: () -> Unit,
    onAddShift: () -> Unit,
    onEditShift: (Shift) -> Unit,
    onDeleteShift: (Shift) -> Unit,
    onMarkWorked: (Shift) -> Unit,
    onCopyLastWeek: () -> Unit,
    onApplyTemplate: () -> Unit,
    onSaveTemplate: () -> Unit,
    onLogPay: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val hasHourly = data.hourlySources.isNotEmpty()
        if (hasHourly || data.sourcesForPay.isNotEmpty()) {
            WeekHeader(
                data = data, showPicker = hasHourly, onPrevious = onPreviousWeek, onNext = onNextWeek, onThisWeek = onThisWeek,
                menuEnabled = true, canBulk = hasHourly,
                onCopyLastWeek = onCopyLastWeek, onApplyTemplate = onApplyTemplate, onSaveTemplate = onSaveTemplate, onLogPay = onLogPay,
            )
        }
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !hasHourly -> EmptyState(
                title = "No shift-based income yet",
                description = "Add an hourly income source in Settings, or in onboarding, and your shifts will show up here.",
            ) {
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }
            data.weekShifts.isEmpty() -> EmptyState(
                title = "No shifts this week",
                description = "Add a shift, or fill the week from your template.",
            ) {
                Button(onClick = onAddShift) { Text("Add shift") }
                OutlinedButton(onClick = onApplyTemplate) { Text("Apply template") }
            }
            else -> WeekList(data, currency, onEditShift, onDeleteShift, onMarkWorked)
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Event, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

@Composable
private fun WeekHeader(
    data: RosterData,
    showPicker: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onThisWeek: () -> Unit,
    menuEnabled: Boolean,
    canBulk: Boolean,
    onCopyLastWeek: () -> Unit,
    onApplyTemplate: () -> Unit,
    onSaveTemplate: () -> Unit,
    onLogPay: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPicker) {
            IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week") }
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    RosterFormat.weekTitle(data.weekStart),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    textAlign = TextAlign.Center,
                )
                if (data.isThisWeek) {
                    Text("This week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TextButton(onClick = onThisWeek) { Text("This week") }
                }
            }
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week") }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = menuEnabled) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Roster actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (canBulk) {
                    DropdownMenuItem(text = { Text("Copy last week") }, onClick = { menuOpen = false; onCopyLastWeek() })
                    DropdownMenuItem(text = { Text("Apply template") }, onClick = { menuOpen = false; onApplyTemplate() })
                    DropdownMenuItem(text = { Text("Save week as template") }, onClick = { menuOpen = false; onSaveTemplate() })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("Log pay received") }, onClick = { menuOpen = false; onLogPay() })
            }
        }
    }
}

@Composable
private fun WeekList(
    data: RosterData,
    currency: String,
    onEdit: (Shift) -> Unit,
    onDelete: (Shift) -> Unit,
    onMarkWorked: (Shift) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (group in data.dayGroups) {
            item(key = "day-${group.date}") { DayHeader(group.date, data.holidayName(group.date)) }
            items(group.lines, key = { "shift-${it.id}" }) { line ->
                ShiftRow(line, currency, onEdit, onDelete, onMarkWorked)
            }
        }
        data.fortnightWarning?.let { w ->
            item(key = "fortnight-warning") {
                SectionCard(title = "Hours limit") {
                    WarningRow(RosterFormat.warningText(w, null), w.level)
                }
            }
        }
        for (footer in data.footers) {
            item(key = "footer-${footer.source.id}") { FooterCard(footer, currency) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayHeader(date: LocalDate, holiday: String?) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp).semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(RosterFormat.day(date), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (holiday != null) HolidayChip(holiday)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShiftRow(
    line: ShiftLine,
    currency: String,
    onEdit: (Shift) -> Unit,
    onDelete: (Shift) -> Unit,
    onMarkWorked: (Shift) -> Unit,
) {
    val current by rememberUpdatedState(line.shift)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(current); true }
                SwipeToDismissBoxValue.StartToEnd -> { onMarkWorked(current); false }
                else -> false
            }
        },
    )
    val shift = line.shift
    val cancelled = shift.status == ShiftStatus.cancelled
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = shift.status != ShiftStatus.worked,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = shiftDescription(line, currency)
                    onClick(label = "Edit shift") { onEdit(shift); true }
                    customActions = buildList {
                        add(CustomAccessibilityAction("Delete shift") { onDelete(shift); true })
                        if (shift.status != ShiftStatus.worked) add(CustomAccessibilityAction("Mark as worked") { onMarkWorked(shift); true })
                    }
                }
                .clickable { onEdit(shift) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(line.sourceName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    RosterMoneyText(line.gross, currency, style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${RosterFormat.time(shift.start)} – ${RosterFormat.time(shift.end)}",
                            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                        )
                        if (shift.crossesMidnight) {
                            Text("finishes next day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "${RosterFormat.hoursFromMinutes(shift.paidMinutes)} paid",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLabel(shift.status)
                    if (!cancelled) line.chips.forEach { BandChip(it) }
                    line.holidayNames.forEach { HolidayChip(it) }
                }
                if (shift.note.isNotEmpty()) {
                    Text(shift.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val delete = direction == SwipeToDismissBoxValue.EndToStart
    val worked = direction == SwipeToDismissBoxValue.StartToEnd
    val color = when {
        delete -> MaterialTheme.colorScheme.errorContainer
        worked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(Modifier.fillMaxSize(), color = color, shape = MaterialTheme.shapes.medium) {
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = if (delete) Alignment.CenterEnd else Alignment.CenterStart) {
            if (delete) Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            if (worked) Icon(Icons.Filled.DoneAll, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun StatusLabel(status: ShiftStatus) {
    val (icon, color) = when (status) {
        ShiftStatus.rostered -> Icons.Filled.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        ShiftStatus.worked -> Icons.Filled.CheckCircle to positiveColor()
        ShiftStatus.cancelled -> Icons.Filled.Cancel to negativeColor()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = color)
        Text(" ${status.title}", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

internal val ShiftStatus.title: String
    get() = when (this) {
        ShiftStatus.rostered -> "Rostered"
        ShiftStatus.worked -> "Worked"
        ShiftStatus.cancelled -> "Cancelled"
    }

internal fun shiftDescription(line: ShiftLine, currency: String): String {
    val s = line.shift
    val parts = mutableListOf(line.sourceName, "${RosterFormat.time(s.start)} to ${RosterFormat.time(s.end)}")
    if (s.crossesMidnight) parts.add("finishes next day")
    parts.add("${RosterFormat.hoursFromMinutes(s.paidMinutes)} paid")
    parts.add(if (s.status == ShiftStatus.cancelled) "no pay" else RosterFormat.money(line.gross, currency))
    parts.add(s.status.title)
    if (line.holidayNames.isNotEmpty()) parts.add("public holiday " + line.holidayNames.joinToString(", "))
    if (s.note.isNotEmpty()) parts.add(s.note)
    return parts.joinToString(", ")
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun FooterCard(footer: SourceFooter, currency: String) {
    SectionCard(title = "${footer.source.name} this week") {
        FooterLine("Hours") { Text(RosterFormat.hoursFromMinutes(footer.paidMinutes), style = TabularNumbers) }
        FooterLine("Gross") { RosterMoneyText(footer.gross, currency) }
        FooterLine("Tax") { RosterMoneyText(footer.tax, currency) }
        FooterLine("Net") { RosterMoneyText(footer.net, currency, style = MaterialTheme.typography.titleMedium) }
        footer.taxPeriod?.let { period ->
            Text(
                "Tax and net cover the pay period ${RosterFormat.shortDate(period.start)} to ${RosterFormat.shortDate(period.end)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        footer.payday?.let { payday ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EventAvailable, contentDescription = null, Modifier.size(18.dp))
                Text(" Paid ${RosterFormat.day(payday)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        footer.warnings.forEach { w -> WarningRow(RosterFormat.warningText(w, footer.source.name), w.level) }
    }
}

@Composable
private fun FooterLine(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        value()
    }
}

// endregion

// region Dialogs

@Composable
private fun BulkConfirmDialog(action: BulkAction, data: RosterData, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = when (action) {
        BulkAction.CopyLastWeek -> "Copy last week?"
        BulkAction.ApplyTemplate -> "Apply template?"
        BulkAction.SaveTemplate -> "Save week as template?"
    }
    val confirm = when (action) {
        BulkAction.CopyLastWeek -> "Copy shifts"
        BulkAction.ApplyTemplate -> "Apply template"
        BulkAction.SaveTemplate -> "Save as template"
    }
    val message = when (action) {
        BulkAction.CopyLastWeek -> data.copyLastWeekPlan().let {
            if (it.hasSource) "${RosterData.summary(it.toAdd.size, it, "This will add")} Nothing is overwritten."
            else "Last week has no shifts to copy."
        }
        BulkAction.ApplyTemplate -> data.applyTemplatePlan().let {
            if (it.hasSource) "${RosterData.summary(it.toAdd.size, it, "This will add")} Existing shifts stay."
            else "There is no template yet. Save a week as a template first."
        }
        BulkAction.SaveTemplate ->
            "Your shifts this week become the default week from ${RosterFormat.shortDate(data.weekStart)} onwards. This changes your forecast."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// endregion
