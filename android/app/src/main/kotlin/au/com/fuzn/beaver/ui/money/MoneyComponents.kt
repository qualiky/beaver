package au.com.fuzn.beaver.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.ui.common.Tone
import au.com.fuzn.beaver.ui.common.title
import au.com.fuzn.beaver.ui.editors.DateField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Shared pieces of the Money tab: sheets, the full-screen editor window, badges and swipe backgrounds. */

/** A pill with a symbol and words, so colour is never the only signal. */
@Composable
fun VarianceChip(tone: Tone, text: String, modifier: Modifier = Modifier) {
    val color = tone.color()
    val icon = tone.symbol
    Surface(
        modifier = modifier.semantics { contentDescription = "Variance: $text" },
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = color)
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** The "Total set" badge shown on a week whose total replaces its entries. */
@Composable
fun TotalSetBadge(modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            "Total set",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** The swipe-to-dismiss backdrop: an icon and a word on a tinted surface, on the side the row is moving from. */
@Composable
fun SwipeBackdrop(direction: SwipeToDismissBoxValue, startLabel: String?, startIcon: ImageVector?, endLabel: String?, endIcon: ImageVector?) {
    val toEnd = direction == SwipeToDismissBoxValue.EndToStart
    val toStart = direction == SwipeToDismissBoxValue.StartToEnd
    val color = when {
        toEnd -> MaterialTheme.colorScheme.errorContainer
        toStart -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val onColor = if (toEnd) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(Modifier.fillMaxSize(), color = color, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = if (toEnd) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (toEnd) endIcon else if (toStart) startIcon else null
            val label = if (toEnd) endLabel else if (toStart) startLabel else null
            if (icon != null) Icon(icon, contentDescription = null, tint = onColor)
            if (label != null) Text(" $label", color = onColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

object SwipeIcons {
    val unmark: ImageVector get() = Icons.Filled.Undo
    val edit: ImageVector get() = Icons.Filled.Edit
    val delete: ImageVector get() = Icons.Filled.Delete
}

private val LocalDateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })

/** A saveable date: survives rotation as an epoch day. */
@Composable
fun rememberSaveableDate(initial: LocalDate): MutableState<LocalDate> =
    rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(initial) }

/**
 * A bottom sheet with Cancel and Save. [onSave] runs in the sheet's scope; on success the sheet closes, on a
 * failure its message shows in the sheet and the sheet stays open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend () -> Unit,
    modifier: Modifier = Modifier,
    saveLabel: String = "Save",
    content: @Composable () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = canSave && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                onSave()
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                busy = false
                                error = e.message ?: "Couldn't save."
                            }
                        }
                    },
                ) { Text(saveLabel) }
            }
        }
    }
}

/** A full-screen window for the shared editors (they bring their own Scaffold with Cancel and Save). */
@Composable
fun FullScreenEditor(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
fun MoneyErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't save") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Category chips for an expense. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpendCategoryChips(selected: SpendCategory, onSelect: (SpendCategory) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SpendCategory.entries.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option.title) })
        }
    }
}

@Composable
fun NoteField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text("Note (optional)") },
        singleLine = true, modifier = modifier.fillMaxWidth(),
    )
}

/** Re-export so sheets read naturally. */
@Composable
fun SheetDateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit) = DateField(label, date, onChange)

/** Runs [onClose] once, after composition. Used when a saved sheet key no longer matches live data. */
@Composable
fun CloseEffect(onClose: () -> Unit) {
    LaunchedEffect(Unit) { onClose() }
}
