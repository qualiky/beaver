package au.com.fuzn.beaver.ui.roster

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** The add/edit shift form in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftEditorSheet(
    editor: ShiftEditorState,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (Shift) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ShiftEditorForm(editor, currency, onCancel = onDismiss, onSave = { onSave(editor.shift) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShiftEditorForm(editor: ShiftEditorState, currency: String, onCancel: () -> Unit, onSave: () -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (editor.existingId == null) "Add shift" else "Edit shift",
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
        )

        if (editor.sources.size > 1) {
            PickerField(
                label = "Source", selected = editor.selectedSource?.name.orEmpty(), options = editor.sources,
                optionText = { it.name }, onSelect = { editor.selectSource(it.id) },
            )
        } else {
            editor.selectedSource?.let { Text("Source: ${it.name}", style = MaterialTheme.typography.bodyLarge) }
        }

        OutlinedButton(onClick = { pickDate = true }, Modifier.fillMaxWidth()) {
            Text("Date: ${RosterFormat.day(editor.date)} ${editor.date.year}", style = TabularNumbers)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickStart = true }) { Text("Start: ${RosterFormat.time(editor.start)}", style = TabularNumbers) }
            OutlinedButton(onClick = { pickEnd = true }) { Text("End: ${RosterFormat.time(editor.end)}", style = TabularNumbers) }
        }
        if (editor.finishesNextDay) {
            Text("Finishes next day", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        BreakControl(editor)

        Text("Status", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val statuses = ShiftStatus.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            statuses.forEachIndexed { index, status ->
                SegmentedButton(
                    selected = editor.status == status,
                    onClick = { editor.status = status },
                    shape = SegmentedButtonDefaults.itemShape(index, statuses.size),
                ) { Text(status.title, maxLines = 1) }
            }
        }
        OutlinedTextField(
            value = editor.note, onValueChange = { editor.note = it }, Modifier.fillMaxWidth(),
            label = { Text("Note") }, minLines = 1, maxLines = 3,
        )

        PreviewCard(editor, currency)

        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onSave, enabled = editor.selectedSource != null) { Text("Save") }
        }
    }

    if (pickDate) DateDialog(editor.date, onDismiss = { pickDate = false }) { editor.date = it; pickDate = false }
    if (pickStart) TimeDialog("Start time", editor.start, onDismiss = { pickStart = false }) { editor.start = it; pickStart = false }
    if (pickEnd) TimeDialog("End time", editor.end, onDismiss = { pickEnd = false }) { editor.end = it; pickEnd = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakControl(editor: ShiftEditorState) {
    Text("Unpaid break", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShiftEditorState.BREAK_PRESETS.forEach { minutes ->
            FilterChip(
                selected = !editor.customBreak && editor.breakMinutes == minutes,
                onClick = { editor.selectBreak(minutes) },
                label = { Text(if (minutes == 0) "None" else "$minutes min") },
            )
        }
        FilterChip(selected = editor.customBreak, onClick = { editor.selectCustomBreak() }, label = { Text("Custom") })
    }
    if (editor.customBreak) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { editor.adjustBreak(editor.breakMinutes - 5) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease break by 5 minutes")
            }
            Text(
                "${editor.breakMinutes} min", style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Custom break"
                    stateDescription = "${editor.breakMinutes} minutes"
                },
            )
            IconButton(onClick = { editor.adjustBreak(editor.breakMinutes + 5) }) {
                Icon(Icons.Filled.Add, contentDescription = "Increase break by 5 minutes")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(editor: ShiftEditorState, currency: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pay preview", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            val pay = editor.pay
            if (pay == null) {
                Text(
                    "This source has no rate card yet, so pay can not be previewed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            pay.bands.forEach { band ->
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    BandChip(RosterFormat.chip(band.band))
                    Text(band.band, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(RosterFormat.hours(band.hoursHundredths), style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers))
                        Text(
                            "${RosterFormat.money(au.com.fuzn.beaver.core.Money(band.rateCentsPerHour.toLong()), currency)}/h",
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (editor.holidayNames.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Public holiday", style = MaterialTheme.typography.bodyMedium)
                    editor.holidayNames.forEach { HolidayChip(it) }
                }
            }
            Text(
                "Paid ${RosterFormat.hoursFromMinutes(pay.paidMinutes)} of ${editor.durationText}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            )
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Gross", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                RosterMoneyText(editor.gross, currency, style = MaterialTheme.typography.titleMedium)
            }
            if (editor.status == ShiftStatus.cancelled) {
                Text("Cancelled shifts earn nothing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A labelled dropdown: a button that shows the current choice and opens a menu of [options]. */
@Composable
internal fun <T> PickerField(
    label: String,
    selected: String,
    options: List<T>,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Text("$label: $selected", Modifier.weight(1f, fill = false), style = TabularNumbers)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionText(option)) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initial.hour, initial.minute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
