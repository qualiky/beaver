package au.com.fuzn.beaver.ui.editors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.ui.theme.BeaverTheme

/** Characters a money field accepts while typing. Everything else is dropped so letters never get in. */
private fun filterMoneyInput(text: String): String = text.filter { it in '0'..'9' || it == '.' || it == ',' || it == '$' }

/**
 * A currency text field. [value] holds the raw text; parse it with [MoneyParser.cents]. The decimal separator is
 * always a dot, so the field never converts or reads a locale comma as a decimal.
 */
@Composable
fun MoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    currencyCode: String = "AUD",
    suffix: String? = null,
    error: String? = null,
) {
    EditorTextField(
        label = label,
        value = value,
        onValueChange = { onValueChange(filterMoneyInput(it)) },
        modifier = modifier,
        error = error,
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        prefix = MoneyParser.symbol(currencyCode),
        suffix = suffix,
    )
}

@Preview(name = "MoneyField", showBackground = true)
@Composable
private fun MoneyFieldPreview() {
    BeaverTheme {
        var text by remember { mutableStateOf("1,234.56") }
        var bad by remember { mutableStateOf("12.345") }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            MoneyField("Amount", text, { text = it })
            MoneyField("Rate", bad, { bad = it }, suffix = "/ hr", error = "Enter an amount such as 49.99.")
            Text("Dot decimal only", style = MaterialTheme.typography.bodySmall)
        }
    }
}
