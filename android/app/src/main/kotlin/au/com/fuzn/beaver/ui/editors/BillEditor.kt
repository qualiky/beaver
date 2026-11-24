package au.com.fuzn.beaver.ui.editors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.LocalDate

// region Cadence kind and presets

/** The cadence choices without their parameters (the day of month and the day count live in the editor). */
enum class BillCadenceKind(val title: String) {
    Weekly("Weekly"),
    Fortnightly("Fortnightly"),
    EveryNDays("Every N days"),
    Monthly("Monthly on a day"),
    Quarterly("Quarterly"),
    Yearly("Yearly"),
    OneOff("One-off");

    companion object {
        fun of(cadence: Cadence): BillCadenceKind = when (cadence) {
            Cadence.Weekly -> Weekly
            Cadence.Fortnightly -> Fortnightly
            is Cadence.EveryNDays -> EveryNDays
            is Cadence.Monthly -> Monthly
            Cadence.Quarterly -> Quarterly
            Cadence.Yearly -> Yearly
            Cadence.OneOff -> OneOff
        }
    }
}

/**
 * A common bill the user can tap to start from. A preset carries no amount: only a name, a category, a sensible
 * default cadence and its own icon.
 */
data class BillPreset(
    val name: String,
    val category: BillCategory,
    val cadence: BillCadenceKind,
    val icon: ImageVector,
) {
    val id: String get() = name

    companion object {
        /** Rent, Phone, Internet, Electricity, Gas, Water, Transport card, Gym, Streaming, Music, Cloud storage, Insurance, Loan repayment. */
        val common: List<BillPreset> = listOf(
            BillPreset("Rent", BillCategory.housing, BillCadenceKind.Weekly, Icons.Filled.House),
            BillPreset("Phone", BillCategory.utilities, BillCadenceKind.Monthly, Icons.Filled.Smartphone),
            BillPreset("Internet", BillCategory.utilities, BillCadenceKind.Monthly, Icons.Filled.Wifi),
            BillPreset("Electricity", BillCategory.utilities, BillCadenceKind.Quarterly, Icons.Filled.Bolt),
            BillPreset("Gas", BillCategory.utilities, BillCadenceKind.Quarterly, Icons.Filled.LocalFireDepartment),
            BillPreset("Water", BillCategory.utilities, BillCadenceKind.Quarterly, Icons.Filled.WaterDrop),
            BillPreset("Transport card", BillCategory.transport, BillCadenceKind.Weekly, Icons.Filled.DirectionsBus),
            BillPreset("Gym", BillCategory.subscription, BillCadenceKind.Fortnightly, Icons.Filled.FitnessCenter),
            BillPreset("Streaming", BillCategory.subscription, BillCadenceKind.Monthly, Icons.Filled.Tv),
            BillPreset("Music", BillCategory.subscription, BillCadenceKind.Monthly, Icons.Filled.MusicNote),
            BillPreset("Cloud storage", BillCategory.subscription, BillCadenceKind.Monthly, Icons.Filled.Cloud),
            BillPreset("Insurance", BillCategory.insurance, BillCadenceKind.Monthly, Icons.Filled.Shield),
            BillPreset("Loan repayment", BillCategory.debt, BillCadenceKind.Fortnightly, Icons.Filled.CreditCard),
        )
    }
}

/**
 * A grid of tappable chips, one per preset. Every chip uses the same text style and a minimum height, labels wrap to
 * two lines, and the chips in a row share the height of the tallest. Selecting calls [onSelect]; the amount is left
 * for the user.
 */
@Composable
fun BillPresetPicker(
    onSelect: (BillPreset) -> Unit,
    modifier: Modifier = Modifier,
    presets: List<BillPreset> = BillPreset.common,
    selectedName: String? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, (maxWidth / 148.dp).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.chunked(columns).forEach { rowPresets ->
                Row(
                    Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPresets.forEach { preset ->
                        PresetChip(preset, preset.name == selectedName, onSelect, Modifier.weight(1f).fillMaxHeight())
                    }
                    // Empty cells keep the last row's chips the same width as the rest.
                    repeat(columns - rowPresets.size) { Column(Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(preset: BillPreset, selected: Boolean, onSelect: (BillPreset) -> Unit, modifier: Modifier) {
    val description = "${preset.name}, ${EditorLabels.categoryTitle(preset.category)}, ${preset.cadence.title}"
    Surface(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(preset) })
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(preset.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            // One fixed text style for every chip, so no chip is smaller than another.
            Text(
                preset.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// endregion

// region State holder

/** State for the bill editor. All cadences; a monthly bill's day of the month follows its next due date. */
class BillEditorModel(bill: Bill? = null, today: LocalDate = LocalDate.now()) : EditorStateSaving {
    enum class Field { Name, Amount, Cadence, DueDate }

    val isNew: Boolean = bill == null
    val id: Int = bill?.id ?: 0
    val touches = FieldTouches<Field>()
    fun touch(field: Field) = touches.touch(field)
    fun revealAllIssues() { touches.revealAll = true }

    private var nameState by mutableStateOf(bill?.name ?: "")
    var name: String
        get() = nameState
        set(v) { nameState = v; touch(Field.Name) }

    private var amountState by mutableStateOf(bill?.let { MoneyParser.text(it.amount.cents) } ?: "")
    var amountText: String
        get() = amountState
        set(v) { amountState = v; touch(Field.Amount) }

    var dayOfMonth by mutableStateOf((bill?.anchorDate ?: today).dayOfMonth)

    private var cadenceKindState by mutableStateOf(bill?.let { BillCadenceKind.of(it.cadence) } ?: BillCadenceKind.Monthly)

    /** Switching to monthly takes the day of the month from the due date. */
    var cadenceKind: BillCadenceKind
        get() = cadenceKindState
        set(v) {
            val old = cadenceKindState
            cadenceKindState = v
            if (v == BillCadenceKind.Monthly && old != BillCadenceKind.Monthly) dayOfMonth = anchorDate.dayOfMonth
        }

    private var everyNDaysState by mutableStateOf((bill?.cadence as? Cadence.EveryNDays)?.n?.toString() ?: "28")
    var everyNDaysText: String
        get() = everyNDaysState
        set(v) { everyNDaysState = v; touch(Field.Cadence) }

    private var anchorState by mutableStateOf(bill?.anchorDate ?: today)

    /**
     * The next due date (the first payment). Changing it on a monthly bill moves the day of the month with it, so a
     * monthly bill never keeps the old day while its due date shows another.
     */
    var anchorDate: LocalDate
        get() = anchorState
        set(v) {
            val old = anchorState
            anchorState = v
            touch(Field.DueDate)
            if (cadenceKind == BillCadenceKind.Monthly && v != old) dayOfMonth = v.dayOfMonth
        }

    var category by mutableStateOf(bill?.category ?: BillCategory.other)
    var account by mutableStateOf(bill?.account ?: "")
    var isActive by mutableStateOf(bill?.isActive ?: true)
    var note by mutableStateOf(bill?.note ?: "")

    var presetName by mutableStateOf<String?>(null)
        private set

    init {
        // An existing monthly bill keeps its stored day, even if the due date was clamped to a short month.
        (bill?.cadence as? Cadence.Monthly)?.let { dayOfMonth = it.dayOfMonth }
    }

    override fun saveState(): ArrayList<Any?> = arrayListOf<Any?>(
        name, amountText, dayOfMonth, cadenceKind.name, everyNDaysText, anchorDate.toString(), category.name, account,
        isActive, note,
    ).also {
        it.putNullable(presetName)
        touches.writeTo(it)
    }

    override fun restoreState(saved: List<Any?>) {
        val r = SavedReader(saved)
        nameState = r.string(); amountState = r.string(); dayOfMonth = r.int()
        cadenceKindState = r.enum(BillCadenceKind.entries.toTypedArray()); everyNDaysState = r.string()
        anchorState = r.date(); category = r.enum(BillCategory.entries.toTypedArray()); account = r.string()
        isActive = r.bool(); note = r.string(); presetName = r.nullableString()
        touches.readFrom(r, Field.entries)
    }

    /** The field's issue, only once it has been edited or a save was attempted. */
    fun visibleIssue(field: Field): String? = if (touches.shows(field)) issue(field) else null

    /** Fills the name, category and cadence from a preset. The amount is left alone. */
    fun apply(preset: BillPreset) {
        name = preset.name
        category = preset.category
        cadenceKind = preset.cadence
        presetName = preset.name
    }

    val cadence: Cadence?
        get() = when (cadenceKind) {
            BillCadenceKind.Weekly -> Cadence.Weekly
            BillCadenceKind.Fortnightly -> Cadence.Fortnightly
            BillCadenceKind.EveryNDays -> EditorNumber.wholeNumber(everyNDaysText)?.let { Cadence.EveryNDays(it) }
            BillCadenceKind.Monthly -> Cadence.Monthly(dayOfMonth)
            BillCadenceKind.Quarterly -> Cadence.Quarterly
            BillCadenceKind.Yearly -> Cadence.Yearly
            BillCadenceKind.OneOff -> Cadence.OneOff
        }

    fun issue(field: Field): String? = when (field) {
        Field.Name -> if (name.isBlank()) "Enter a name, such as Rent." else null
        Field.Amount -> {
            val cents = MoneyParser.cents(amountText)
            if (cents == null) "Enter an amount such as 49.99."
            else if (cents <= 0) "The amount must be more than zero."
            else null
        }
        Field.Cadence -> when (cadenceKind) {
            BillCadenceKind.EveryNDays -> {
                val n = EditorNumber.wholeNumber(everyNDaysText)
                if (n == null || n !in 1..365) "Enter a number of days from 1 to 365." else null
            }
            BillCadenceKind.Monthly -> if (dayOfMonth in 1..31) null else "Choose a day from 1 to 31."
            else -> null
        }
        Field.DueDate -> null
    }

    val isValid: Boolean get() = Field.entries.all { issue(it) == null }

    fun makeBill(): Bill? {
        if (!isValid) return null
        val cents = MoneyParser.cents(amountText) ?: return null
        val cadence = cadence ?: return null
        val label = account.trim()
        return Bill(
            id = id, name = name.trim(), amount = Money(cents), cadence = cadence, anchorDate = anchorDate,
            category = category, account = label.ifEmpty { null }, isActive = isActive, note = note,
        )
    }

    suspend fun save(repository: Repository): Bill {
        val bill = makeBill() ?: run {
            revealAllIssues()
            throw EditorInvalidException()
        }
        return repository.save(bill)
    }

    suspend fun delete(repository: Repository) {
        if (id != 0) repository.deleteBill(id)
    }
}

// endregion

// region Composables

@Composable
internal fun BillEditorForm(model: BillEditorModel, currencyCode: String, onDelete: (() -> Unit)?) {
    if (model.isNew) {
        EditorSection("Common bills") {
            BillPresetPicker(onSelect = { model.apply(it) }, selectedName = model.presetName)
        }
    }

    EditorSection("Bill") {
        EditorTextField(
            "Name", model.name, { model.name = it },
            error = model.visibleIssue(BillEditorModel.Field.Name), capitalization = KeyboardCapitalization.Words,
        )
        MoneyField(
            "Amount", model.amountText, { model.amountText = it },
            currencyCode = currencyCode, error = model.visibleIssue(BillEditorModel.Field.Amount),
        )
        DropdownField("Category", BillCategory.entries, model.category, { model.category = it }, EditorLabels::categoryTitle)
    }

    EditorSection("Schedule", footer = scheduleFooter(model.cadenceKind)) {
        DropdownField(
            "Repeats", BillCadenceKind.entries, model.cadenceKind, { model.cadenceKind = it }, { it.title },
        )
        if (model.cadenceKind == BillCadenceKind.EveryNDays) {
            EditorTextField(
                "Number of days between payments", model.everyNDaysText, { model.everyNDaysText = it },
                keyboardType = KeyboardType.Number, placeholder = "28", suffix = "days",
            )
        }
        if (model.cadenceKind == BillCadenceKind.Monthly) {
            StepperRow(
                "On day ${model.dayOfMonth} of the month", model.dayOfMonth, { model.dayOfMonth = it }, 1..31,
                description = "Day of the month",
            )
        }
        EditorFieldMessage(model.visibleIssue(BillEditorModel.Field.Cadence))
        DateField(
            if (model.cadenceKind == BillCadenceKind.OneOff) "Due date" else "Next due",
            model.anchorDate, { model.anchorDate = it },
        )
    }

    EditorSection("Details (optional)") {
        EditorTextField("Account label, e.g. Everyday card", model.account, { model.account = it })
        EditorTextField("Note", model.note, { model.note = it }, singleLine = false)
    }

    EditorSection(null, footer = "Inactive bills are left out of the forecast.") {
        SwitchRow("Active", model.isActive, { model.isActive = it })
    }

    if (!model.isNew && onDelete != null) {
        EditorDeleteSection("Delete bill", "This also deletes its payment history. It can't be undone.", onDelete)
    }
}

internal fun scheduleFooter(kind: BillCadenceKind): String = when (kind) {
    BillCadenceKind.Monthly ->
        "The next due date is the first payment. After that it falls on the chosen day, or the last day of a short month."
    BillCadenceKind.Quarterly, BillCadenceKind.Yearly -> "Repeats from the next due date, keeping its day of the month."
    BillCadenceKind.OneOff -> "Counted once, on this date."
    else -> "Repeats from the next due date. Nothing is counted before it."
}

/**
 * Create or edit a bill with any cadence. For a new bill the common-bill chips are shown at the top; pass [preset]
 * to start from one. [onSaved] receives the stored bill; [onDeleted] runs after a delete.
 */
@Composable
fun BillEditor(
    repository: Repository,
    onSaved: (Bill) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    bill: Bill? = null,
    preset: BillPreset? = null,
    currencyCode: String = "AUD",
    onDeleted: (() -> Unit)? = null,
) {
    val model = rememberSaveable(
        bill?.id,
        saver = editorSaver { BillEditorModel(bill) },
    ) { BillEditorModel(bill).also { m -> preset?.let(m::apply) } }
    val runner = rememberEditorRunner()
    EditorScaffold(
        title = if (model.isNew) "New bill" else "Edit bill",
        canSave = model.isValid, onCancel = onDismiss, modifier = modifier, runner = runner,
        onSave = { runner.run({ model.save(repository) }, onSaved) },
    ) {
        BillEditorForm(model, currencyCode, onDelete = { runner.run({ model.delete(repository) }, { onDeleted?.invoke() }) })
    }
}

// endregion

@Preview(name = "New bill", showBackground = true, heightDp = 1800)
@Composable
private fun BillEditorNewPreview() {
    BeaverTheme {
        val model = remember { BillEditorModel(null, LocalDate.of(2026, 11, 23)) }
        EditorScaffold("New bill", canSave = false, onCancel = {}, onSave = {}) {
            BillEditorForm(model, "AUD", null)
        }
    }
}

@Preview(name = "Demo bill", showBackground = true, heightDp = 1200)
@Composable
private fun BillEditorDemoPreview() {
    BeaverTheme {
        val model = remember { BillEditorModel(DemoData.bills[2]) }
        EditorScaffold("Edit bill", canSave = true, onCancel = {}, onSave = {}) {
            BillEditorForm(model, "AUD", onDelete = {})
        }
    }
}

@Preview(name = "Preset chips", showBackground = true, widthDp = 360)
@Composable
private fun BillPresetPickerPreview() {
    BeaverTheme { Column(Modifier.padding(16.dp)) { BillPresetPicker({}, selectedName = "Gym") } }
}

@Preview(name = "Preset chips (large font)", showBackground = true, widthDp = 360, fontScale = 1.8f)
@Composable
private fun BillPresetPickerLargeFontPreview() {
    BeaverTheme { Column(Modifier.padding(16.dp)) { BillPresetPicker({}, selectedName = "Cloud storage") } }
}
