package au.com.fuzn.beaver.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.WithholdingRow
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.ui.common.tabular
import au.com.fuzn.beaver.ui.editors.EditorFieldMessage
import au.com.fuzn.beaver.ui.editors.EditorScaffold
import au.com.fuzn.beaver.ui.editors.EditorSection
import au.com.fuzn.beaver.ui.editors.EditorTextField

/** The built-in table, read-only, with "Duplicate to edit". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltinWithholdingView(
    table: WithholdingTable,
    onDuplicate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Built-in table", Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = "Read-only", Modifier.size(20.dp))
                Text(table.name, style = MaterialTheme.typography.titleMedium)
            }
            Text(table.note ?: "Verify against official sources before relying on it.")
            Text(
                "Verify against official sources. This table may be out of date.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditorSection("Rows (read-only)") {
                table.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    WithholdingRowSummary(row)
                }
            }
            OutlinedButton(onClick = onDuplicate, Modifier.fillMaxWidth()) { Text("Duplicate to edit") }
            Text(
                "Makes an editable copy. The built-in table stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WithholdingRowSummary(row: WithholdingRow) {
    val limit = row.belowDollars?.let { "Below $$it" } ?: "No upper limit"
    val coefficients = "a ${WithholdingRowDraft.text(row.aTenThousandths)}, b ${WithholdingRowDraft.text(row.bTenThousandths)}"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {
            contentDescription = "$limit, $coefficients"
        },
    ) {
        Text(limit, style = MaterialTheme.typography.bodyMedium)
        Text(
            coefficients,
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Row editor for a custom table. `belowX` in whole dollars, `a` and `b` as decimal strings. */
@Composable
fun WithholdingTableEditor(
    table: WithholdingTable,
    onSave: (WithholdingTable) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = remember(table.id) { WithholdingEditorModel(table) }
    EditorScaffold(
        title = "Edit table",
        canSave = model.isValid,
        onCancel = onBack,
        onSave = { model.build()?.let(onSave) },
        modifier = modifier,
    ) {
        EditorSection("Name") {
            EditorTextField("Name", model.name, { model.name = it }, error = model.nameIssue)
        }
        EditorSection(
            "Rows",
            footer = "Tax = a x weekly pay - b, using the first row whose limit is above the pay. " +
                "Leave the last row's limit empty. Verify against official sources.",
        ) {
            model.drafts.forEachIndexed { index, draft ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Row ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { model.removeRow(draft) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove row ${index + 1}")
                        }
                    }
                    EditorTextField(
                        "Below x (whole dollars)", draft.belowX, { draft.belowX = it },
                        placeholder = "No limit", keyboardType = KeyboardType.Number,
                    )
                    EditorTextField("a", draft.a, { draft.a = it }, keyboardType = KeyboardType.Decimal)
                    EditorTextField("b", draft.b, { draft.b = it }, keyboardType = KeyboardType.Decimal)
                    EditorFieldMessage(model.issue(draft))
                }
            }
            OutlinedButton(onClick = model::addRow) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add row", Modifier.padding(start = 8.dp))
            }
            EditorFieldMessage(model.orderIssue)
        }
    }
}
