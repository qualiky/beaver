package au.com.fuzn.beaver.ui.editors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.ClockWindow
import au.com.fuzn.beaver.core.DaySelector
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.RateRule
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.util.UUID

// region Draft rule

/** One editable line of a rate card. Text fields stay as text until validated. */
data class RateRuleDraft(
    val id: String = UUID.randomUUID().toString(),
    val band: String = "",
    val rateText: String = "",
    val days: DaySelector = DaySelector.weekdays,
    val hasWindow: Boolean = false,
    val fromMinute: Int = 9 * 60,
    val toMinute: Int = 17 * 60,
    /** Set once the user edits the band or rate, so a fresh blank row shows no errors. */
    val bandTouched: Boolean = false,
    val rateTouched: Boolean = false,
) {
    constructor(rule: RateRule) : this(
        band = rule.band,
        rateText = MoneyParser.text(rule.centsPerHour),
        days = rule.days,
        hasWindow = rule.window != null,
        fromMinute = rule.window?.startMinute ?: 9 * 60,
        toMinute = rule.window?.endMinute ?: 17 * 60,
    )

    val bandIssue: String? get() = if (band.isBlank()) "Enter a band name, such as Weekday evening." else null

    private val rateCents: Int?
        get() = MoneyParser.cents(rateText)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    val rateIssue: String?
        get() {
            val cents = rateCents ?: return "Enter an hourly rate such as 30.00."
            return if (cents > 0) null else "The rate must be more than zero."
        }

    val windowIssue: String?
        get() {
            if (!hasWindow) return null
            if (fromMinute !in 0 until 1440 || toMinute !in 1..1440 || fromMinute >= toMinute) {
                return "The window must end after it starts. Use 24:00 for midnight."
            }
            return null
        }

    val isValid: Boolean get() = bandIssue == null && rateIssue == null && windowIssue == null

    /** The engine rule, or null while any field is invalid. */
    val rule: RateRule?
        get() {
            if (!isValid) return null
            val cents = rateCents ?: return null
            return RateRule(band.trim(), cents, days, if (hasWindow) ClockWindow(fromMinute, toMinute) else null)
        }
}

// endregion

// region State holder

/**
 * State for the rate card editor. Plain Kotlin on Compose snapshot state: no Android types, so it runs on the JVM.
 * Pristine forms show no errors; [visibleNameIssue] and friends reveal them after an edit or a save attempt.
 */
class RateCardEditorModel(card: RateCard? = null, id: Int = card?.id ?: 0) : EditorStateSaving {
    var id: Int = id
        private set

    private var nameState by mutableStateOf(card?.name ?: "")
    var name: String
        get() = nameState
        set(value) {
            nameState = value
            nameTouched = true
        }

    val rules = mutableStateListOf<RateRuleDraft>().apply { card?.rules?.forEach { add(RateRuleDraft(it)) } }

    /** Set by editing, or by [revealAllIssues] on a failed save. */
    var nameTouched by mutableStateOf(false)
        private set
    var rulesTouched by mutableStateOf(false)
        private set
    var revealAll by mutableStateOf(false)
        private set

    fun revealAllIssues() {
        revealAll = true
    }

    override fun saveState(): ArrayList<Any?> = arrayListOf<Any?>(id, name, nameTouched, rulesTouched, revealAll).also { out ->
        out.putList(rules) { d ->
            add(d.id); add(d.band); add(d.rateText); add(d.days.name); add(d.hasWindow)
            add(d.fromMinute); add(d.toMinute); add(d.bandTouched); add(d.rateTouched)
        }
    }

    override fun restoreState(saved: List<Any?>) {
        val r = SavedReader(saved)
        id = r.int(); nameState = r.string(); nameTouched = r.bool(); rulesTouched = r.bool(); revealAll = r.bool()
        val restored = r.list {
            RateRuleDraft(
                id = string(), band = string(), rateText = string(), days = enum(DaySelector.entries.toTypedArray()),
                hasWindow = bool(), fromMinute = int(), toMinute = int(), bandTouched = bool(), rateTouched = bool(),
            )
        }
        rules.clear()
        rules.addAll(restored)
    }

    val visibleNameIssue: String? get() = if (nameTouched || revealAll) nameIssue else null
    val visibleRulesIssue: String? get() = if (rulesTouched || revealAll) rulesIssue else null
    fun visibleBandIssue(d: RateRuleDraft): String? = if (d.bandTouched || revealAll) d.bandIssue else null
    fun visibleRateIssue(d: RateRuleDraft): String? = if (d.rateTouched || revealAll) d.rateIssue else null

    /** Replaces this model's contents (including its id) with a stored card. */
    fun adopt(card: RateCard) {
        id = card.id
        nameState = card.name
        rules.clear()
        card.rules.forEach { rules.add(RateRuleDraft(it)) }
    }

    // Editing

    /** Replaces all rules with "Typical casual (template)". The name is kept unless it is empty. */
    fun applyTypicalCasual() {
        val template = RateCard.typicalCasual()
        rules.clear()
        template.rules.forEach { rules.add(RateRuleDraft(it)) }
        rulesTouched = true
        if (name.isBlank()) nameState = template.name
    }

    fun addRule() {
        rules.add(RateRuleDraft())
        rulesTouched = true
    }

    fun removeRule(id: String) {
        rules.removeAll { it.id == id }
        rulesTouched = true
    }

    fun moveRule(id: String, offset: Int) {
        val i = rules.indexOfFirst { it.id == id }
        val j = i + offset
        if (i < 0 || j !in rules.indices) return
        val moved = rules.removeAt(i)
        rules.add(j, moved)
    }

    /** Applies [change] to the rule with this id. */
    fun update(id: String, change: (RateRuleDraft) -> RateRuleDraft) {
        val i = rules.indexOfFirst { it.id == id }
        if (i >= 0) rules[i] = change(rules[i])
    }

    fun setBand(id: String, text: String) = update(id) { it.copy(band = text, bandTouched = true) }
    fun setRate(id: String, text: String) = update(id) { it.copy(rateText = text, rateTouched = true) }
    fun setDays(id: String, days: DaySelector) = update(id) { it.copy(days = days) }
    fun setHasWindow(id: String, on: Boolean) = update(id) { it.copy(hasWindow = on) }
    fun setFrom(id: String, minute: Int) = update(id) { it.copy(fromMinute = minute) }
    fun setTo(id: String, minute: Int) = update(id) { it.copy(toMinute = minute) }

    // Validation

    val nameIssue: String? get() = if (name.isBlank()) "Enter a name for this rate card." else null

    val rulesIssue: String? get() = if (rules.isEmpty()) "Add at least one rate, or start from the template." else null

    /** Rules that are fully valid so far, in order. */
    val validRules: List<RateRule> get() = rules.mapNotNull { it.rule }

    /** Weekday times no rule prices. A warning only: it never blocks saving. */
    val gaps: List<ClockWindow> get() = RateCard(name = "", rules = validRules).weekdayGaps()

    val gapWarning: String?
        get() {
            if (rules.isEmpty() || gaps.isEmpty()) return null
            val list = gaps.joinToString(", ") { EditorLabels.window(it) }
            return "No rate covers weekdays at $list. Shifts in those times would be paid nothing."
        }

    /** True when the rules (not the name) are ready to save. Used when the card is embedded in another editor. */
    val rulesAreValid: Boolean get() = rulesIssue == null && rules.all { it.isValid }

    val isValid: Boolean get() = nameIssue == null && rulesAreValid

    /** The card to save, or null while invalid. [nameOverride] is used by editors that hide the name field. */
    fun makeCard(nameOverride: String? = null): RateCard? {
        if (!rulesAreValid) return null
        val cardName = (nameOverride ?: name).trim()
        if (cardName.isEmpty()) return null
        return RateCard(id = id, name = cardName, rules = validRules)
    }

    /** Saves the card. An existing card keeps its id, so editing never creates a second card. */
    suspend fun save(repository: Repository, nameOverride: String? = null): RateCard {
        val card = makeCard(nameOverride) ?: run {
            revealAllIssues()
            throw EditorInvalidException()
        }
        val stored = repository.save(card)
        id = stored.id
        return stored
    }
}

// endregion

// region Composables

/** The rate card sections, for embedding in a form (the income source editor) or the standalone editor. */
@Composable
fun RateCardSections(
    model: RateCardEditorModel,
    modifier: Modifier = Modifier,
    currencyCode: String = "AUD",
    showsName: Boolean = true,
) {
    var confirmTemplate by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showsName) {
            EditorSection("Name") {
                EditorTextField(
                    "Rate card name", model.name, { model.name = it },
                    error = model.visibleNameIssue, capitalization = KeyboardCapitalization.Words,
                )
            }
        }

        EditorSection(
            "Rates",
            footer = "Rates are examples only. Enter the dollars per hour from your own contract.",
        ) {
            OutlinedButton(
                onClick = { if (model.rules.isEmpty()) model.applyTypicalCasual() else confirmTemplate = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
                Text("Start from Typical casual (template)", Modifier.padding(start = 8.dp))
            }
        }

        model.rules.forEachIndexed { index, draft ->
            RuleCard(model, index, draft, currencyCode)
        }

        EditorSection(
            null,
            footer = "The first matching rate wins. A public holiday rate overrides the others on holidays.",
        ) {
            OutlinedButton(onClick = { model.addRule() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AddCircle, contentDescription = null)
                Text("Add rate", Modifier.padding(start = 8.dp))
            }
            EditorFieldMessage(model.visibleRulesIssue)
            model.gapWarning?.let { EditorWarning(it) }
        }
    }
    if (confirmTemplate) {
        AlertDialog(
            onDismissRequest = { confirmTemplate = false },
            title = { Text("Replace the current rates?") },
            text = { Text("The template uses made-up example rates. Edit each band's rate afterwards.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTemplate = false
                    model.applyTypicalCasual()
                }) { Text("Replace with template") }
            },
            dismissButton = { TextButton(onClick = { confirmTemplate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RuleCard(model: RateCardEditorModel, index: Int, draft: RateRuleDraft, currencyCode: String) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rate ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for rate ${index + 1}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                        enabled = index > 0,
                        onClick = { menu = false; model.moveRule(draft.id, -1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                        enabled = index < model.rules.size - 1,
                        onClick = { menu = false; model.moveRule(draft.id, 1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; model.removeRule(draft.id) },
                    )
                }
            }
            EditorTextField(
                "Band name", draft.band, { model.setBand(draft.id, it) },
                error = model.visibleBandIssue(draft), capitalization = KeyboardCapitalization.Sentences,
            )
            DropdownField(
                "Days", DaySelector.entries, draft.days, { model.setDays(draft.id, it) }, EditorLabels::dayTitle,
            )
            SwitchRow("Only part of the day", draft.hasWindow, { model.setHasWindow(draft.id, it) })
            if (draft.hasWindow) {
                MinuteField("From", draft.fromMinute, { model.setFrom(draft.id, it) }, 0..1425)
                MinuteField("To", draft.toMinute, { model.setTo(draft.id, it) }, 15..1440)
                EditorFieldMessage(draft.windowIssue)
            }
            MoneyField(
                "Rate", draft.rateText, { model.setRate(draft.id, it) },
                currencyCode = currencyCode, suffix = "/ hr", error = model.visibleRateIssue(draft),
            )
        }
    }
}

/** The stateless body of the standalone editor, shared with previews. */
@Composable
internal fun RateCardEditorForm(model: RateCardEditorModel, currencyCode: String) {
    RateCardSections(model, currencyCode = currencyCode)
}

/**
 * Create or edit a rate card on its own. Pass `card = null` to create. Saves through [repository] and calls
 * [onSaved] with the stored card; [onDismiss] runs on Cancel.
 */
@Composable
fun RateCardEditor(
    repository: Repository,
    onSaved: (RateCard) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    card: RateCard? = null,
    currencyCode: String = "AUD",
) {
    val model = rememberSaveable(card?.id, saver = editorSaver { RateCardEditorModel(card) }) { RateCardEditorModel(card) }
    val runner = rememberEditorRunner()
    EditorScaffold(
        title = "Rate card", canSave = model.isValid, onCancel = onDismiss, modifier = modifier, runner = runner,
        onSave = { runner.run({ model.save(repository) }, onSaved) },
    ) {
        RateCardEditorForm(model, currencyCode)
    }
}

// endregion

@Preview(name = "Rate card (demo)", showBackground = true, heightDp = 1200)
@Composable
private fun RateCardEditorPreview() {
    BeaverTheme {
        EditorScaffold("Rate card", canSave = true, onCancel = {}, onSave = {}) {
            RateCardEditorForm(RateCardEditorModel(DemoData.rateCard), "AUD")
        }
    }
}

@Preview(name = "Rate card (empty)", showBackground = true)
@Composable
private fun RateCardEditorEmptyPreview() {
    BeaverTheme {
        EditorScaffold("Rate card", canSave = false, onCancel = {}, onSave = {}) {
            RateCardEditorForm(RateCardEditorModel(), "AUD")
        }
    }
}
