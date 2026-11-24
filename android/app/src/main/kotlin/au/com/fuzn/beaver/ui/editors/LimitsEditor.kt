package au.com.fuzn.beaver.ui.editors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.LocalDate

/** Everything the limits editor manages: the fortnightly hours limit, public holidays and unavailable ranges. */
data class LimitsValue(
    val fortnightLimit: FortnightLimit? = null,
    val holidays: List<Holiday> = emptyList(),
    val unavailable: List<UnavailableRange> = emptyList(),
)

/**
 * State for the limits editor. It edits one [LimitsValue] and writes the difference on [save], so Cancel discards
 * everything. It cannot save before the stored values have loaded ([isLoaded]), so it never wipes data by mistake.
 * Only one fortnight limit is kept: a save deletes the others.
 */
class LimitsEditorModel(value: LimitsValue? = null, today: LocalDate = LocalDate.now()) : EditorStateSaving {
    enum class Field { Hours }

    var hasLimit by mutableStateOf(false)
    private var hoursState by mutableStateOf("")
    var hoursText: String
        get() = hoursState
        set(v) { hoursState = v; touch(Field.Hours) }
    var effectiveFrom by mutableStateOf(today)

    var holidays by mutableStateOf(emptyList<Holiday>())
        private set

    /** New rows carry a negative temporary id until saved. */
    var unavailable by mutableStateOf(emptyList<UnavailableRange>())
        private set
    var isLoaded by mutableStateOf(false)
        private set

    val touches = FieldTouches<Field>()
    fun touch(field: Field) = touches.touch(field)
    fun revealAllIssues() { touches.revealAll = true }
    fun visibleIssue(field: Field): String? = if (touches.shows(field)) issue(field) else null

    private var original = LimitsValue()
    private var nextTempId = -1

    /** True once typed state was restored, so [load] keeps it and only records what is stored. */
    private var formRestored = false

    override fun saveState(): ArrayList<Any?> = arrayListOf<Any?>(
        hasLimit, hoursText, effectiveFrom.toString(), nextTempId,
    ).also { out ->
        out.putList(holidays) { add(it.date.toString()); add(it.name) }
        out.putList(unavailable) { add(it.id); add(it.from.toString()); add(it.to.toString()); add(it.note) }
        touches.writeTo(out)
    }

    override fun restoreState(saved: List<Any?>) {
        val r = SavedReader(saved)
        hasLimit = r.bool(); hoursState = r.string(); effectiveFrom = r.date(); nextTempId = r.int()
        holidays = r.list { Holiday(date = date(), name = string()) }
        unavailable = r.list { UnavailableRange(id = int(), from = date(), to = date(), note = string()) }
        touches.readFrom(r, Field.entries)
        formRestored = true
    }

    init {
        if (value != null) apply(value)
    }

    private fun apply(value: LimitsValue) {
        original = value
        hasLimit = value.fortnightLimit != null
        hoursState = value.fortnightLimit?.hoursPerFortnight?.toString() ?: ""
        value.fortnightLimit?.let { effectiveFrom = it.effectiveFrom }
        holidays = value.holidays.sortedBy { it.date }
        unavailable = value.unavailable.sortedBy { it.from }
        isLoaded = true
    }

    /** Reads the stored limits (the latest fortnight limit is the one shown). */
    suspend fun load(repository: Repository) {
        if (isLoaded) return
        val limit = repository.hoursLimits().maxByOrNull { it.effectiveFrom }
        val stored = LimitsValue(limit, repository.holidays(), repository.unavailableRanges())
        if (formRestored) {
            original = stored
            isLoaded = true
        } else {
            apply(stored)
        }
    }

    // Validation

    fun issue(field: Field): String? = when (field) {
        Field.Hours -> {
            if (!hasLimit) null
            else {
                val hours = EditorNumber.wholeNumber(hoursText)
                if (hours == null || hours !in 1..336) "Enter whole hours from 1 to 336." else null
            }
        }
    }

    val isValid: Boolean get() = Field.entries.all { issue(it) == null }

    /** Why a holiday can't be saved, or null. [editing] is the date the row had before editing. */
    fun holidayIssue(name: String, date: LocalDate, editing: LocalDate?): String? {
        if (name.isBlank()) return "Enter a name, such as Christmas Day."
        if (date != editing && holidays.any { it.date == date }) return "A holiday is already set on this date."
        return null
    }

    fun unavailableIssue(from: LocalDate, to: LocalDate): String? =
        if (to < from) "The end date can't be before the start date." else null

    // List editing

    /** Adds a holiday, or replaces the one that was on [editing]. Returns false (and changes nothing) if invalid. */
    fun saveHoliday(holiday: Holiday, editing: LocalDate? = null): Boolean {
        val trimmed = holiday.copy(name = holiday.name.trim())
        if (holidayIssue(trimmed.name, trimmed.date, editing) != null) return false
        holidays = (holidays.filter { it.date != editing } + trimmed).sortedBy { it.date }
        return true
    }

    fun removeHoliday(date: LocalDate) {
        holidays = holidays.filter { it.date != date }
    }

    /** Adds a range (id 0) or replaces the one with the same id. Returns false if invalid. */
    fun saveUnavailable(range: UnavailableRange): Boolean {
        if (unavailableIssue(range.from, range.to) != null) return false
        var r = range.copy(note = range.note.trim())
        val index = if (r.id != 0) unavailable.indexOfFirst { it.id == r.id } else -1
        unavailable = if (index >= 0) {
            unavailable.toMutableList().also { it[index] = r }
        } else {
            if (r.id == 0) {
                r = r.copy(id = nextTempId)
                nextTempId -= 1
            }
            unavailable + r
        }.sortedBy { it.from }
        return true
    }

    fun removeUnavailable(id: Int) {
        unavailable = unavailable.filter { it.id != id }
    }

    // Value and repository

    fun makeValue(): LimitsValue? {
        if (!isValid) return null
        var limit: FortnightLimit? = null
        if (hasLimit) {
            EditorNumber.wholeNumber(hoursText)?.let {
                limit = FortnightLimit(original.fortnightLimit?.id ?: 0, it, effectiveFrom)
            }
        }
        return LimitsValue(limit, holidays, unavailable.map { if (it.id < 0) it.copy(id = 0) else it })
    }

    /** Writes the differences to the repository and returns what is now stored. */
    suspend fun save(repository: Repository): LimitsValue {
        val value = makeValue()?.takeIf { isLoaded } ?: run {
            revealAllIssues()
            throw EditorInvalidException()
        }

        // Fortnight limit: keep one row at most.
        val storedLimits = repository.hoursLimits()
        val kept = value.fortnightLimit?.let { repository.save(it) }
        storedLimits.filter { it.id != kept?.id }.forEach { repository.deleteHoursLimit(it.id) }

        // Holidays are keyed by date.
        val keepDates = value.holidays.map { it.date }.toSet()
        repository.holidays().filter { it.date !in keepDates }.forEach { repository.deleteHoliday(it.date) }
        value.holidays.forEach { repository.save(it) }

        // Unavailable ranges are keyed by id.
        val keepIds = value.unavailable.map { it.id }.toSet()
        repository.unavailableRanges().filter { it.id !in keepIds }.forEach { repository.deleteUnavailableRange(it.id) }
        val storedRanges = value.unavailable.map { repository.save(it) }

        val result = LimitsValue(kept, value.holidays, storedRanges)
        apply(result)
        return result
    }
}

// region Composables

private data class HolidayDraft(val original: LocalDate?, val date: LocalDate, val name: String)

@Composable
internal fun LimitsEditorForm(model: LimitsEditorModel, today: LocalDate) {
    var holidayDraft by remember { mutableStateOf<HolidayDraft?>(null) }
    var unavailableDraft by remember { mutableStateOf<UnavailableRange?>(null) }

    EditorSection(
        "Hours limit",
        footer = "Covers all income sources together, counted in fortnights starting on the Monday of the effective " +
            "date. Warnings only.",
    ) {
        SwitchRow("Fortnightly hours limit", model.hasLimit, { model.hasLimit = it })
        if (model.hasLimit) {
            EditorTextField(
                "Hours per fortnight", model.hoursText, { model.hoursText = it },
                error = model.visibleIssue(LimitsEditorModel.Field.Hours),
                placeholder = "48", keyboardType = KeyboardType.Number, suffix = "per fortnight",
            )
            DateField("Effective from", model.effectiveFrom, { model.effectiveFrom = it })
        }
    }

    EditorSection(
        "Public holidays",
        footer = "Hours worked on these dates use the public holiday rate. None are pre-filled because they depend " +
            "on your region.",
    ) {
        model.holidays.forEach { holiday ->
            ListRow(
                title = holiday.name, subtitle = EditorLabels.date(holiday.date),
                hint = "Edit holiday",
                onClick = { holidayDraft = HolidayDraft(holiday.date, holiday.date, holiday.name) },
                onDelete = { model.removeHoliday(holiday.date) },
                deleteDescription = "Delete ${holiday.name}",
            )
        }
        OutlinedButton(
            onClick = { holidayDraft = HolidayDraft(null, today, "") }, modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AddCircle, contentDescription = null)
            Text("Add public holiday", Modifier.padding(start = 8.dp))
        }
    }

    EditorSection(
        "Unavailable dates",
        footer = "Default-week shifts aren't added on these dates, for travel or exams.",
    ) {
        model.unavailable.forEach { range ->
            val title = if (range.from == range.to) EditorLabels.date(range.from)
            else "${EditorLabels.date(range.from)} to ${EditorLabels.date(range.to)}"
            ListRow(
                title = title, subtitle = range.note.ifEmpty { null },
                hint = "Edit unavailable dates",
                onClick = { unavailableDraft = range },
                onDelete = { model.removeUnavailable(range.id) },
                deleteDescription = "Delete unavailable dates $title",
            )
        }
        OutlinedButton(
            onClick = { unavailableDraft = UnavailableRange(from = today, to = today) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AddCircle, contentDescription = null)
            Text("Add unavailable dates", Modifier.padding(start = 8.dp))
        }
    }

    holidayDraft?.let { draft ->
        HolidayDialog(draft, model, onDismiss = { holidayDraft = null })
    }
    unavailableDraft?.let { draft ->
        UnavailableDialog(draft, model, onDismiss = { unavailableDraft = null })
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    hint: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteDescription: String,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(title, subtitle, hint).joinToString(". ")
                    },
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = deleteDescription) }
        }
    }
}

@Composable
private fun HolidayDialog(draft: HolidayDraft, model: LimitsEditorModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(draft.name) }
    var date by remember { mutableStateOf(draft.date) }
    var edited by remember { mutableStateOf(false) }
    val issue = model.holidayIssue(name, date, draft.original)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.original == null) "New holiday" else "Edit holiday") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorTextField(
                    "Name", name, { name = it; edited = true },
                    capitalization = KeyboardCapitalization.Words,
                )
                DateField("Date", date, { date = it; edited = true })
                EditorFieldMessage(if (edited) issue else null)
                if (draft.original != null) {
                    TextButton(onClick = {
                        model.removeHoliday(draft.original)
                        onDismiss()
                    }) { Text("Delete holiday", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = issue == null,
                onClick = { if (model.saveHoliday(Holiday(date, name), draft.original)) onDismiss() },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UnavailableDialog(draft: UnavailableRange, model: LimitsEditorModel, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(draft.from) }
    var to by remember { mutableStateOf(draft.to) }
    var note by remember { mutableStateOf(draft.note) }
    val issue = model.unavailableIssue(from, to)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0) "New dates" else "Edit dates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("From", from, { from = it })
                DateField("To", to, { to = it })
                EditorFieldMessage(issue)
                EditorTextField("Note (optional)", note, { note = it })
                if (draft.id != 0) {
                    TextButton(onClick = {
                        model.removeUnavailable(draft.id)
                        onDismiss()
                    }) { Text("Delete dates", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = issue == null,
                onClick = { if (model.saveUnavailable(draft.copy(from = from, to = to, note = note))) onDismiss() },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Edit the fortnightly hours limit (with its effective-from date), public holidays and unavailable ranges together.
 * With `value = null` the stored limits are loaded from [repository]. [onSaved] receives what is now stored.
 */
@Composable
fun LimitsEditor(
    repository: Repository,
    onSaved: (LimitsValue) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    value: LimitsValue? = null,
) {
    val model = rememberSaveable(saver = editorSaver { LimitsEditorModel(value) }) { LimitsEditorModel(value) }
    val runner = rememberEditorRunner()
    LaunchedEffect(model) { model.load(repository) }
    EditorScaffold(
        title = "Limits and dates",
        canSave = model.isLoaded && model.isValid, onCancel = onDismiss, modifier = modifier, runner = runner,
        onSave = { runner.run({ model.save(repository) }, onSaved) },
    ) {
        LimitsEditorForm(model, LocalDate.now())
    }
}

// endregion

@Preview(name = "Limits (demo)", showBackground = true, heightDp = 1400)
@Composable
private fun LimitsEditorPreview() {
    BeaverTheme {
        val model = remember {
            LimitsEditorModel(
                LimitsValue(
                    FortnightLimit(1, 76, DemoData.referenceToday),
                    DemoData.holidays, DemoData.unavailable,
                ),
                DemoData.referenceToday,
            )
        }
        EditorScaffold("Limits and dates", canSave = true, onCancel = {}, onSave = {}) {
            LimitsEditorForm(model, DemoData.referenceToday)
        }
    }
}

@Preview(name = "Limits (empty)", showBackground = true)
@Composable
private fun LimitsEditorEmptyPreview() {
    BeaverTheme {
        val model = remember { LimitsEditorModel(LimitsValue(), DemoData.referenceToday) }
        EditorScaffold("Limits and dates", canSave = true, onCancel = {}, onSave = {}) {
            LimitsEditorForm(model, DemoData.referenceToday)
        }
    }
}
