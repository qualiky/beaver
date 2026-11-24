package au.com.fuzn.beaver.ui.editors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// Shared Material 3 building blocks for the editors. Every control has a TalkBack description, no height is fixed
// (text can wrap at large font sizes), and the content scrolls.

/** Runs saves and deletes off the composition, tracks a busy flag and holds the error shown in the "Couldn't save" dialog. */
@Stable
class EditorRunner(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    /** Runs [block]; on success calls [onDone]. Ignored while another run is in progress. */
    fun <T> run(block: suspend () -> T, onDone: (T) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = block()
                busy = false
                onDone(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                errorMessage = e.message ?: "Something went wrong."
            }
        }
    }
}

@Composable
fun rememberEditorRunner(): EditorRunner {
    val scope = rememberCoroutineScope()
    return remember(scope) { EditorRunner(scope) }
}

/** The editor chrome: title, Close (cancel), Save (disabled until valid) and the save-error dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScaffold(
    title: String,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    runner: EditorRunner? = null,
    saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = canSave && runner?.busy != true,
                        modifier = Modifier.semantics {
                            if (!canSave) stateDescription = "Fix the highlighted fields first"
                        },
                    ) { Text(saveLabel) }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
    val message = runner?.errorMessage
    if (message != null) {
        AlertDialog(
            onDismissRequest = { runner.errorMessage = null },
            title = { Text("Couldn't save") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { runner.errorMessage = null }) { Text("OK") } },
        )
    }
}

/** A titled group of fields with an optional footer. */
@Composable
fun EditorSection(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A small red inline message under an invalid field. Renders nothing when [text] is null. */
@Composable
fun EditorFieldMessage(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Problem: $text" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** An orange-ish, non-blocking warning. */
@Composable
fun EditorWarning(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Warning: $text" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** A single-line text field with an inline error. [error] is shown only when the caller says it is visible. */
@Composable
fun EditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    singleLine: Boolean = true,
    suffix: String? = null,
    prefix: String? = null,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (error != null) error(error) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        singleLine = singleLine,
        minLines = 1,
        maxLines = if (singleLine) 1 else 4,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        prefix = prefix?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        supportingText = (error ?: supportingText)?.let { { Text(it) } },
    )
}

/** A labelled on/off row. The whole row toggles, and TalkBack reads it as a switch. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A minus/plus stepper for a bounded whole number. */
@Composable
fun StepperRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    valueText: String = value.toString(),
    description: String = label,
) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$description, $valueText"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onValueChange((value - step).coerceAtLeast(range.first)) }, enabled = value > range.first) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $description")
        }
        Text(valueText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
        IconButton(onClick = { onValueChange((value + step).coerceAtMost(range.last)) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $description")
        }
    }
}

/** A row of mutually exclusive choices (segmented buttons). */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label(option)) },
            )
        }
    }
}

/** The look shared by the date, time and dropdown fields: a labelled outlined surface that opens something when tapped. */
@Composable
private fun PickerSurface(
    label: String,
    valueText: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasError: Boolean = false,
) {
    val borderColor = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $valueText" },
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, borderColor),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(valueText, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A dropdown of fixed options. [selected] null shows [placeholder]. */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    hasError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        PickerSurface(
            label = label,
            valueText = selected?.let(optionLabel) ?: placeholder,
            icon = Icons.Filled.ArrowDropDown,
            onClick = { expanded = true },
            hasError = hasError,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** A date field that opens the Material 3 date picker. Dates are zone-free `LocalDate`s. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, onDateChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    PickerSurface(label, EditorLabels.date(date), Icons.Filled.CalendarMonth, { open = true }, modifier)
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(it.toPickerDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** A 24-hour time field that opens the Material 3 time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, time: LocalTime, onTimeChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    PickerSurface(label, EditorLabels.clock(time.hour * 60 + time.minute), Icons.Filled.Schedule, { open = true }, modifier)
    if (open) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { Box(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = state) } },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** A dropdown of minutes since midnight in 15 minute steps; the current value is always offered. */
@Composable
fun MinuteField(label: String, minute: Int, onMinuteChange: (Int) -> Unit, range: IntRange, modifier: Modifier = Modifier) {
    val options = remember(minute, range) {
        ((0..1440 step 15).filter { it in range } + minute).distinct().sorted()
    }
    DropdownField(label, options, minute, onMinuteChange, EditorLabels::clock, modifier)
}

/** A destructive "Delete ..." row that asks for confirmation first. */
@Composable
fun EditorDeleteSection(title: String, message: String, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    HorizontalDivider()
    OutlinedButton(
        onClick = { confirming = true },
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null)
        Text(title, Modifier.padding(start = 8.dp))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// The Material date picker works in UTC milliseconds at midnight, so convert zone-free dates through UTC.
internal fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toPickerDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
