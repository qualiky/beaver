package au.com.fuzn.beaver.ui.money

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.app.AppEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class MoneySegment(val title: String) { Bills("Bills"), Spending("Spending"), Goals("Goals") }

/**
 * Runs a write from the UI. Failures are reported through [onError] (shown in a dialog) rather than crashing.
 */
class MoneyRunner(
    val actions: MoneyActions,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    fun perform(block: suspend MoneyActions.() -> Unit) = launch { actions.block() }
}

/**
 * The Money tab: Bills, Spending and Goals behind a segmented control. It reads the live store input, so it updates
 * as the database changes. The selected segment, the Monthly toggle and open sheets are saved state and survive
 * rotation (sheets are saved as a short key and rebuilt from the live data).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyScreen(env: AppEnv, modifier: Modifier = Modifier) {
    val input by env.store.input.collectAsState()
    val settings by env.store.settings.collectAsState()
    var segment by rememberSaveable { mutableStateOf(MoneySegment.Bills) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val runner = remember(env.repository, scope) {
        MoneyRunner(MoneyActions(env.repository)) { block ->
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: "Something went wrong."
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            MoneySegment.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == segment,
                    onClick = { segment = option },
                    shape = SegmentedButtonDefaults.itemShape(index, MoneySegment.entries.size),
                ) { Text(option.title) }
            }
        }
        when (segment) {
            MoneySegment.Bills -> BillsSection(input, settings.currency, env.repository, runner, Modifier.weight(1f))
            MoneySegment.Spending -> SpendingSection(input, settings.currency, env.repository, runner, Modifier.weight(1f))
            MoneySegment.Goals -> GoalsSection(input, settings.currency, env.repository, runner, Modifier.weight(1f))
        }
    }
    error?.let { MoneyErrorDialog(it) { error = null } }
}
