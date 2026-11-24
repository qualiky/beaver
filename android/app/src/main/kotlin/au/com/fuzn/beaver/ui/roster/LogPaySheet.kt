package au.com.fuzn.beaver.ui.roster

import au.com.fuzn.beaver.ui.editors.MoneyField
import au.com.fuzn.beaver.ui.editors.MoneyParser
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The "Log pay received" bottom sheet, wired to [RosterState]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogPaySheet(state: RosterState, log: LogPayState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Keep the form's view of the roster current (the "already logged" check) and recompute the expected net.
    LaunchedEffect(state.data) { log.data = state.data }
    LaunchedEffect(log.sourceId, log.payday, state.data) {
        log.expected = log.source?.let { state.expectedNet(it, log.payday) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LogPayForm(
            log = log,
            currency = state.currency,
            recent = state.payRows,
            onClose = onDismiss,
            onSave = {
                val pay = log.toPay() ?: return@LogPayForm
                val updating = log.existing != null
                scope.launch {
                    if (state.logPay(pay.sourceID, pay.payDate, pay.net, pay.gross, pay.tax, pay.note)) {
                        state.show(if (updating) "Pay updated." else "Pay logged.")
                        onDismiss()
                    }
                }
            },
            onDeletePay = { row -> scope.launch { state.deletePay(row.pay) } },
        )
    }
}

@Composable
internal fun LogPayForm(
    log: LogPayState,
    currency: String,
    recent: List<PayRow>,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onDeletePay: (PayRow) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Log pay received", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

        val sources = log.data.sourcesForPay
        if (sources.isEmpty()) {
            Text("No income sources. Add an income source in Settings first.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClose, Modifier.padding(bottom = 16.dp)) { Text("Close") }
            return@Column
        }

        PickerField("Source", log.source?.name.orEmpty(), sources, { it.name }, { log.selectSource(it.id) })
        PickerField("Payday", RosterFormat.day(log.payday), log.paydays, { RosterFormat.day(it) }, { log.selectPayday(it) })
        log.expected?.let { expected ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Expected net", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                RosterMoneyText(expected, currency)
            }
        }

        Text("Actual pay", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        LogMoneyField("Net received", log.netText, { log.netText = it }, currency)
        LogMoneyField("Gross", log.grossText, { log.grossText = it }, currency, optional = true)
        LogMoneyField("Tax", log.taxText, { log.taxText = it }, currency, optional = true)
        OutlinedTextField(
            value = log.note, onValueChange = { log.note = it }, Modifier.fillMaxWidth(),
            label = { Text("Note") }, minLines = 1, maxLines = 3,
        )
        log.difference?.let { diff ->
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Difference", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                DifferenceLabel(diff, currency)
            }
        }
        if (log.existing != null) {
            Text(
                "Saving will update the pay already logged for this payday.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onClose) { Text("Close") }
            Button(onClick = onSave, enabled = log.canSave) { Text(if (log.existing != null) "Update" else "Save") }
        }

        HorizontalDivider()
        Text("Recent pays", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (recent.isEmpty()) {
            Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent.forEach { row -> RecentPayRow(row, currency, onTap = { log.load(row.pay) }, onDelete = { onDeletePay(row) }) }
        Column(Modifier.padding(bottom = 16.dp)) {}
    }
}

@Composable
private fun RecentPayRow(row: PayRow, currency: String, onTap: () -> Unit, onDelete: () -> Unit) {
    val diff = row.difference
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable(onClickLabel = "Load this pay into the form", onClick = onTap).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.sourceName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                RosterMoneyText(row.pay.net, currency)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    RosterFormat.day(row.pay.payDate), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diff != null) DifferenceLabel(diff, currency)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete pay for ${row.sourceName} on ${RosterFormat.day(row.pay.payDate)}")
        }
    }
}

/** The shared [MoneyField] with the Roster's "optional" label and invalid-amount message. */
@Composable
private fun LogMoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    currency: String,
    optional: Boolean = false,
) {
    val invalid = value.isNotBlank() && MoneyParser.cents(value) == null
    MoneyField(
        label = if (optional) "$label (optional)" else label,
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        currencyCode = currency,
        error = if (invalid) "Enter an amount like 1234.50" else null,
    )
}
