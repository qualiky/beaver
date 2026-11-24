package au.com.fuzn.beaver.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.common.MoneyInput
import au.com.fuzn.beaver.ui.common.tabular
import au.com.fuzn.beaver.ui.common.title
import kotlinx.coroutines.launch

/** Quick-add for a living expense (amount, category, note). The caller saves it dated today. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseSheet(
    currency: String,
    onDismiss: () -> Unit,
    onSave: suspend (cents: Long, category: SpendCategory, note: String) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SpendCategory.food) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val cents = MoneyInput.cents(amountText)?.takeIf { it > 0 }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add expense", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                prefix = { Text(FigureFormatter.narrowSymbol(currency)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyLarge.tabular(),
                isError = amountText.isNotBlank() && cents == null,
                supportingText = if (amountText.isNotBlank() && cents == null) {
                    { Text("Enter an amount like 12.50") }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = "Amount in $currency" },
            )
            Text("Category", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpendCategory.entries.forEach { option ->
                    FilterChip(selected = option == category, onClick = { category = option }, label = { Text(option.title) })
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it }, label = { Text("Note (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = cents != null,
                    onClick = {
                        val value = cents ?: return@Button
                        scope.launch {
                            runCatching { onSave(value, category, note) }
                                .onSuccess { onDismiss() }
                                .onFailure { error = it.message ?: "Couldn't save." }
                        }
                    },
                ) { Text("Save") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
