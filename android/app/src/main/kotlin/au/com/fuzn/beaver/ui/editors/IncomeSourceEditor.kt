package au.com.fuzn.beaver.ui.editors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.TaxMethod
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.DayOfWeek
import java.time.LocalDate

enum class IncomeScheduleKind(val title: String) {
    Weekly("Weekly"), Fortnightly("Fortnightly"), Monthly("Monthly"),
}

enum class IncomeTaxKind(val title: String) {
    None("None"), Flat("Flat %"), Table("Table"),
}

/**
 * State for the income source editor (hourly with an embedded rate card, or a fixed amount).
 *
 * Editing an hourly source never creates a second rate card: the embedded [rateCard] model starts with the source's
 * existing card id, [load] adopts the stored card, and Save stays disabled until the load has finished.
 */
class IncomeSourceEditorModel(
    source: IncomeSource? = null,
    newKind: IncomeKind = IncomeKind.hourly,
    today: LocalDate = LocalDate.now(),
) : EditorStateSaving {
    enum class Field { Name, Rates, Amount, Schedule, Tax, SoftCap, HardCap }

    val isNew: Boolean = source == null
    val id: Int = source?.id ?: 0
    private val existingRateCardId: Int? = source?.rateCardID

    /** The embedded rate card. Its id is the existing card's id, so a save updates that card in place. */
    val rateCard = RateCardEditorModel(id = existingRateCardId ?: 0)

    val touches = FieldTouches<Field>()
    fun touch(field: Field) = touches.touch(field)

    // Plain fields: the setters mark the field as edited.
    private var nameState by mutableStateOf(source?.name ?: "")
    var name: String
        get() = nameState
        set(v) { nameState = v; touch(Field.Name) }

    private var kindState by mutableStateOf(source?.kind ?: newKind)
    var kind: IncomeKind
        get() = kindState
        set(v) {
            kindState = v
            if (v == IncomeKind.hourly && scheduleKind == IncomeScheduleKind.Monthly) scheduleKind = IncomeScheduleKind.Weekly
        }

    private var fixedAmountState by mutableStateOf(source?.fixedAmount?.let { MoneyParser.text(it.cents) } ?: "")
    var fixedAmountText: String
        get() = fixedAmountState
        set(v) { fixedAmountState = v; touch(Field.Amount) }

    var amountIsNet by mutableStateOf(source?.amountIsNet ?: false)
    var isActive by mutableStateOf(source?.isActive ?: true)

    private var softCapState by mutableStateOf(source?.weeklyCapSoftHours?.toString() ?: "")
    var softCapText: String
        get() = softCapState
        set(v) { softCapState = v; touch(Field.SoftCap); touch(Field.HardCap) }

    private var hardCapState by mutableStateOf(source?.weeklyCapHardHours?.toString() ?: "")
    var hardCapText: String
        get() = hardCapState
        set(v) { hardCapState = v; touch(Field.HardCap) }

    // Schedule: Wednesday, lag 1 (the spec default), anchor on today.
    var scheduleKind by mutableStateOf(IncomeScheduleKind.Weekly)
    var payWeekday by mutableStateOf(DayOfWeek.WEDNESDAY)
    var lagWeeks by mutableStateOf(1)
    var anchorPayday by mutableStateOf(today)
    var dayOfMonth by mutableStateOf(1)

    // Tax
    private var taxKindState by mutableStateOf(IncomeTaxKind.None)
    var taxKind: IncomeTaxKind
        get() = taxKindState
        set(v) {
            taxKindState = v
            touch(Field.Tax)
            // Picking "Table" selects the first stored table until the user chooses another.
            if (v == IncomeTaxKind.Table && tableId == null) tableId = tables.firstOrNull()?.id
        }
    private var flatPercentState by mutableStateOf("")
    var flatPercentText: String
        get() = flatPercentState
        set(v) { flatPercentState = v; touch(Field.Tax) }
    var tableId by mutableStateOf<String?>(null)

    /** Withholding tables the user can pick (built-in first). Filled by [load]. */
    val tables = mutableStateListOf<WithholdingTable>()
    var isLoaded by mutableStateOf(false)
        private set

    init {
        when (val s = source?.schedule) {
            is PaySchedule.Weekly -> { payWeekday = s.payWeekday; lagWeeks = s.lagWeeks }
            is PaySchedule.Fortnightly -> {
                scheduleKind = IncomeScheduleKind.Fortnightly; anchorPayday = s.anchorPayday; lagWeeks = s.lagWeeks
            }
            is PaySchedule.Monthly -> { scheduleKind = IncomeScheduleKind.Monthly; dayOfMonth = s.dayOfMonth }
            null -> Unit
        }
        when (val t = source?.taxMethod) {
            is TaxMethod.FlatPercent -> { taxKindState = IncomeTaxKind.Flat; flatPercentState = MoneyParser.text(t.basisPoints) }
            is TaxMethod.Table -> { taxKindState = IncomeTaxKind.Table; tableId = t.id }
            else -> Unit
        }
    }

    /** True once typed state was restored, so [load] does not replace the typed rate card. */
    private var formRestored = false

    override fun saveState(): ArrayList<Any?> = arrayListOf<Any?>(
        name, kind.name, fixedAmountText, amountIsNet, isActive, softCapText, hardCapText, scheduleKind.name,
        payWeekday.name, lagWeeks, anchorPayday.toString(), dayOfMonth, taxKind.name, flatPercentText,
    ).also {
        it.putNullable(tableId)
        touches.writeTo(it)
        it.add(rateCard.saveState())
    }

    @Suppress("UNCHECKED_CAST")
    override fun restoreState(saved: List<Any?>) {
        val r = SavedReader(saved)
        nameState = r.string(); kindState = r.enum(IncomeKind.entries.toTypedArray()); fixedAmountState = r.string()
        amountIsNet = r.bool(); isActive = r.bool(); softCapState = r.string(); hardCapState = r.string()
        scheduleKind = r.enum(IncomeScheduleKind.entries.toTypedArray()); payWeekday = r.enum(DayOfWeek.entries.toTypedArray())
        lagWeeks = r.int(); anchorPayday = r.date(); dayOfMonth = r.int()
        taxKindState = r.enum(IncomeTaxKind.entries.toTypedArray()); flatPercentState = r.string(); tableId = r.nullableString()
        touches.readFrom(r, Field.entries)
        rateCard.restoreState(saved.last() as List<Any?>)
        formRestored = true
    }

    /** Loads withholding tables and the existing rate card. Call once before the form is used. */
    suspend fun load(repository: Repository) {
        tables.clear()
        tables.addAll(repository.withholdingTables())
        existingRateCardId?.let { cardId ->
            repository.rateCards().firstOrNull { it.id == cardId }?.let { if (!formRestored) rateCard.adopt(it) }
        }
        if (taxKind == IncomeTaxKind.Table && tableId == null) tableId = tables.firstOrNull()?.id
        isLoaded = true
    }

    /** Shows every error, as after a failed save attempt. */
    fun revealAllIssues() {
        touches.revealAll = true
        rateCard.revealAllIssues()
    }

    /** The field's issue, only once it has been edited or a save was attempted. */
    fun visibleIssue(field: Field): String? {
        if (field == Field.Rates) return if (touches.revealAll || rateCard.rulesTouched) issue(Field.Rates) else null
        return if (touches.shows(field)) issue(field) else null
    }

    // Derived

    val availableSchedules: List<IncomeScheduleKind>
        get() = if (kind == IncomeKind.hourly) listOf(IncomeScheduleKind.Weekly, IncomeScheduleKind.Fortnightly)
        else IncomeScheduleKind.entries

    /** Fixed pay entered as net is never taxed. */
    val taxApplies: Boolean get() = !(kind == IncomeKind.fixed && amountIsNet)

    val schedule: PaySchedule
        get() = when (scheduleKind) {
            IncomeScheduleKind.Weekly -> PaySchedule.Weekly(payWeekday, lagWeeks)
            IncomeScheduleKind.Fortnightly -> PaySchedule.Fortnightly(anchorPayday, lagWeeks)
            IncomeScheduleKind.Monthly -> PaySchedule.Monthly(dayOfMonth)
        }

    // Validation (one message per invalid field)

    fun issue(field: Field): String? = when (field) {
        Field.Name -> if (name.isBlank()) "Enter a name, such as Example Bar." else null
        Field.Rates ->
            if (kind != IncomeKind.hourly) null
            else if (rateCard.rulesAreValid) null else "Add at least one valid rate below."
        Field.Amount -> {
            if (kind != IncomeKind.fixed) null
            else {
                val cents = MoneyParser.cents(fixedAmountText)
                if (cents == null) "Enter an amount such as 1,200.00."
                else if (cents <= 0) "The amount must be more than zero."
                else null
            }
        }
        Field.Schedule -> when {
            scheduleKind == IncomeScheduleKind.Monthly && kind == IncomeKind.hourly -> "Monthly pay is only for fixed amounts."
            scheduleKind == IncomeScheduleKind.Monthly && dayOfMonth !in 1..31 -> "Choose a day from 1 to 31."
            scheduleKind != IncomeScheduleKind.Monthly && lagWeeks !in 0..8 -> "Choose a lag from 0 to 8 weeks."
            else -> null
        }
        Field.Tax -> if (!taxApplies) null else when (taxKind) {
            IncomeTaxKind.None -> null
            IncomeTaxKind.Flat -> {
                val bp = MoneyParser.basisPoints(flatPercentText)
                if (bp == null) "Enter a percentage such as 15 or 12.5."
                else if (bp !in 1..10_000) "The percentage must be above 0 and at most 100."
                else null
            }
            IncomeTaxKind.Table -> {
                val chosen = tableId
                if (chosen == null || tables.none { it.id == chosen }) {
                    if (tables.isEmpty()) "No withholding tables are stored yet." else "Choose a withholding table."
                } else null
            }
        }
        Field.SoftCap -> capIssue(softCapText)
        Field.HardCap -> capIssue(hardCapText) ?: run {
            val soft = EditorNumber.wholeNumber(softCapText)
            val hard = EditorNumber.wholeNumber(hardCapText)
            if (soft != null && hard != null && hard < soft) "The hard limit can't be below the soft limit." else null
        }
    }

    private fun capIssue(text: String): String? {
        if (text.isBlank()) return null
        val hours = EditorNumber.wholeNumber(text)
        return if (hours == null || hours !in 1..168) "Enter whole hours from 1 to 168, or leave blank." else null
    }

    val isValid: Boolean get() = Field.entries.all { issue(it) == null }

    val taxMethod: TaxMethod?
        get() {
            if (!taxApplies) return TaxMethod.None
            return when (taxKind) {
                IncomeTaxKind.None -> TaxMethod.None
                IncomeTaxKind.Flat -> MoneyParser.basisPoints(flatPercentText)?.let { TaxMethod.FlatPercent(it) }
                IncomeTaxKind.Table -> tableId?.let { TaxMethod.Table(it) }
            }
        }

    /** The source to save, or null while invalid. [rateCardId] is the stored rate card for hourly sources. */
    fun makeSource(rateCardId: Int?): IncomeSource? {
        if (!isValid) return null
        val tax = taxMethod ?: return null
        return IncomeSource(
            id = id,
            name = name.trim(),
            kind = kind,
            rateCardID = if (kind == IncomeKind.hourly) rateCardId else null,
            fixedAmount = if (kind == IncomeKind.fixed) MoneyParser.cents(fixedAmountText)?.let { Money(it) } else null,
            amountIsNet = kind == IncomeKind.fixed && amountIsNet,
            schedule = schedule,
            taxMethod = tax,
            weeklyCapSoftHours = EditorNumber.wholeNumber(softCapText),
            weeklyCapHardHours = EditorNumber.wholeNumber(hardCapText),
            isActive = isActive,
        )
    }

    /** Saves the rate card (hourly) and then the source. Returns the stored source. */
    suspend fun save(repository: Repository): IncomeSource {
        if (!isValid) {
            revealAllIssues()
            throw EditorInvalidException()
        }
        var cardId: Int? = null
        if (kind == IncomeKind.hourly) {
            cardId = rateCard.save(repository, nameOverride = "${name.trim()} rates").id
        }
        val source = makeSource(cardId) ?: throw EditorInvalidException()
        return repository.save(source)
    }

    /** Deletes the source. Its rate card goes too when no other source uses it. */
    suspend fun delete(repository: Repository) {
        if (id == 0) return
        repository.deleteIncomeSource(id)
        val cardId = existingRateCardId ?: return
        if (repository.incomeSources().none { it.rateCardID == cardId }) repository.deleteRateCard(cardId)
    }
}

// region Composables

/** The stateless body of the income source editor, shared with previews. */
@Composable
internal fun IncomeSourceEditorForm(
    model: IncomeSourceEditorModel,
    currencyCode: String,
    onEditTemplates: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    EditorSection("Income") {
        ChoiceRow(
            listOf(IncomeKind.hourly, IncomeKind.fixed), model.kind, { model.kind = it },
            { if (it == IncomeKind.hourly) "Hourly (shifts)" else "Fixed amount" },
        )
        EditorTextField(
            "Name", model.name, { model.name = it },
            error = model.visibleIssue(IncomeSourceEditorModel.Field.Name),
            capitalization = KeyboardCapitalization.Words,
        )
    }

    if (model.kind == IncomeKind.hourly) {
        RateCardSections(model.rateCard, currencyCode = currencyCode, showsName = false)
        EditorFieldMessage(model.visibleIssue(IncomeSourceEditorModel.Field.Rates))
        if (!model.isNew && onEditTemplates != null) {
            OutlinedButton(onClick = onEditTemplates, modifier = Modifier.fillMaxWidth()) {
                Text("Default week of shifts")
            }
        }
    } else {
        EditorSection("Amount", footer = "Net amounts are not taxed again.") {
            MoneyField(
                "Amount per pay", model.fixedAmountText, { model.fixedAmountText = it },
                currencyCode = currencyCode, error = model.visibleIssue(IncomeSourceEditorModel.Field.Amount),
            )
            ChoiceRow(listOf(false, true), model.amountIsNet, { model.amountIsNet = it }, {
                if (it) "Net (take-home)" else "Gross (before tax)"
            })
        }
    }

    ScheduleSection(model)
    if (model.taxApplies) TaxSection(model)

    EditorSection("Weekly hours limits (optional)", footer = "Warnings only. Saving a shift is never blocked.") {
        EditorTextField(
            "Soft limit", model.softCapText, { model.softCapText = it },
            error = model.visibleIssue(IncomeSourceEditorModel.Field.SoftCap),
            placeholder = "None", keyboardType = KeyboardType.Number, suffix = "hours",
        )
        EditorTextField(
            "Hard limit", model.hardCapText, { model.hardCapText = it },
            error = model.visibleIssue(IncomeSourceEditorModel.Field.HardCap),
            placeholder = "None", keyboardType = KeyboardType.Number, suffix = "hours",
        )
    }

    EditorSection(null, footer = "Inactive sources are left out of the forecast.") {
        SwitchRow("Active", model.isActive, { model.isActive = it })
    }

    if (!model.isNew && onDelete != null) {
        EditorDeleteSection(
            "Delete income source",
            "This also deletes its shifts, templates and logged pay. It can't be undone.",
            onConfirm = onDelete,
        )
    }
}

@Composable
private fun ScheduleSection(model: IncomeSourceEditorModel) {
    EditorSection("Pay schedule") {
        if (model.availableSchedules.size > 1) {
            ChoiceRow(model.availableSchedules, model.scheduleKind, { model.scheduleKind = it }, { it.title })
        }
        when (model.scheduleKind) {
            IncomeScheduleKind.Weekly -> {
                DropdownField(
                    "Payday", DayOfWeek.entries, model.payWeekday, { model.payWeekday = it }, EditorLabels::weekdayName,
                )
                LagStepper(model)
            }
            IncomeScheduleKind.Fortnightly -> {
                DateField("Payday (any past or future one)", model.anchorPayday, { model.anchorPayday = it })
                LagStepper(model)
            }
            IncomeScheduleKind.Monthly -> StepperRow(
                "Day of month: ${model.dayOfMonth}", model.dayOfMonth, { model.dayOfMonth = it }, 1..31,
                description = "Day of month",
            )
        }
        EditorFieldMessage(model.visibleIssue(IncomeSourceEditorModel.Field.Schedule))
    }
}

@Composable
private fun LagStepper(model: IncomeSourceEditorModel) {
    if (model.kind != IncomeKind.hourly) return
    StepperRow(
        "Weeks after work: ${model.lagWeeks}", model.lagWeeks, { model.lagWeeks = it }, 0..8,
        description = "Weeks between the end of the work week and payday",
    )
    Text(
        "1 means the week just finished is paid on the next payday.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TaxSection(model: IncomeSourceEditorModel) {
    EditorSection(
        "Tax",
        footer = if (model.taxKind == IncomeTaxKind.None) "No tax is taken: pay is treated as already net."
        else "Tax is worked out on each pay period's gross.",
    ) {
        ChoiceRow(IncomeTaxKind.entries, model.taxKind, { model.taxKind = it }, { it.title })
        when (model.taxKind) {
            IncomeTaxKind.None -> Unit
            IncomeTaxKind.Flat -> EditorTextField(
                "Flat tax percent", model.flatPercentText, { model.flatPercentText = it.filter { c -> c in '0'..'9' || c == '.' } },
                placeholder = "0", keyboardType = KeyboardType.Decimal, suffix = "%",
            )
            IncomeTaxKind.Table -> DropdownField(
                "Table", model.tables.toList(), model.tables.firstOrNull { it.id == model.tableId },
                { model.tableId = it.id; model.touch(IncomeSourceEditorModel.Field.Tax) }, { it.name },
            )
        }
        EditorFieldMessage(model.visibleIssue(IncomeSourceEditorModel.Field.Tax))
    }
}

/**
 * Create or edit an income source (hourly with a rate card, or a fixed amount).
 * Pass `source = null` to create (starting as [kind]). [onSaved] receives the stored source; [onDeleted] runs after
 * a delete; [onEditTemplates] opens the default-week editor for an existing hourly source.
 */
@Composable
fun IncomeSourceEditor(
    repository: Repository,
    onSaved: (IncomeSource) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    kind: IncomeKind = IncomeKind.hourly,
    source: IncomeSource? = null,
    currencyCode: String = "AUD",
    onDeleted: (() -> Unit)? = null,
    onEditTemplates: ((sourceId: Int) -> Unit)? = null,
) {
    val model = rememberSaveable(
        source?.id,
        saver = editorSaver { IncomeSourceEditorModel(source, kind) },
    ) { IncomeSourceEditorModel(source, kind) }
    val runner = rememberEditorRunner()
    LaunchedEffect(model) { model.load(repository) }
    EditorScaffold(
        title = if (model.isNew) "New income" else "Edit income",
        canSave = model.isLoaded && model.isValid,
        onCancel = onDismiss, modifier = modifier, runner = runner,
        onSave = { runner.run({ model.save(repository) }, onSaved) },
    ) {
        IncomeSourceEditorForm(
            model, currencyCode,
            onEditTemplates = onEditTemplates?.let { open -> { open(model.id) } },
            onDelete = { runner.run({ model.delete(repository) }, { onDeleted?.invoke() }) },
        )
    }
}

// endregion

@Preview(name = "Income source (demo hourly)", showBackground = true, heightDp = 1600)
@Composable
private fun IncomeSourceEditorPreview() {
    BeaverTheme {
        val model = remember {
            IncomeSourceEditorModel(DemoData.source).also { it.rateCard.adopt(DemoData.rateCard) }
        }
        EditorScaffold("Edit income", canSave = true, onCancel = {}, onSave = {}) {
            IncomeSourceEditorForm(model, "AUD", onEditTemplates = {}, onDelete = {})
        }
    }
}

@Preview(name = "Income source (new fixed)", showBackground = true, heightDp = 1200)
@Composable
private fun IncomeSourceEditorFixedPreview() {
    BeaverTheme {
        val model = remember { IncomeSourceEditorModel(null, IncomeKind.fixed, LocalDate.of(2026, 11, 23)) }
        Column { EditorScaffold("New income", canSave = false, onCancel = {}, onSave = {}) {
            IncomeSourceEditorForm(model, "AUD", null, null)
        } }
    }
}
