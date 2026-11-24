package au.com.fuzn.beaver.ui.editors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.TemplateShift
import au.com.fuzn.beaver.core.minutesSinceMidnight
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// region Draft shift

/** One editable shift in a default week. */
data class TemplateShiftDraft(
    val id: String = UUID.randomUUID().toString(),
    val weekday: DayOfWeek = DayOfWeek.MONDAY,
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(17, 0),
    val breakMinutes: Int = 30,
) {
    constructor(shift: TemplateShift) : this(
        weekday = shift.weekday, start = shift.start, end = shift.end, breakMinutes = shift.breakMinutes,
    )

    /** An end at or before the start means the shift finishes the next day. */
    val crossesMidnight: Boolean get() = end <= start

    val durationMinutes: Int
        get() = if (crossesMidnight) 1440 - start.minutesSinceMidnight + end.minutesSinceMidnight
        else end.minutesSinceMidnight - start.minutesSinceMidnight

    val issue: String?
        get() = when {
            start == end -> "Start and end can't be the same time."
            breakMinutes < 0 -> "The break can't be negative."
            breakMinutes >= durationMinutes -> "The break must be shorter than the shift."
            else -> null
        }

    val shift: TemplateShift? get() = if (issue == null) TemplateShift(weekday, start, end, breakMinutes) else null
}

// endregion

// region State holders

/** State for editing one default week (template) of an hourly source. */
class TemplateEditorModel(
    sourceId: Int,
    template: Template? = null,
    today: LocalDate = LocalDate.now(),
) {
    val isNew: Boolean = template == null
    val id: Int = template?.id ?: 0
    val sourceId: Int = template?.sourceID ?: sourceId

    private var nameState by mutableStateOf(template?.name ?: "Default week")
    var name: String
        get() = nameState
        set(v) { nameState = v; nameTouched = true }

    var hasEffectiveFrom by mutableStateOf(template?.effectiveFrom != null)
    var effectiveFrom by mutableStateOf(template?.effectiveFrom ?: today)
    val shifts = mutableStateListOf<TemplateShiftDraft>().apply { template?.shifts?.forEach { add(TemplateShiftDraft(it)) } }

    var nameTouched by mutableStateOf(false)
        private set
    var shiftsTouched by mutableStateOf(false)
        private set
    var revealAll by mutableStateOf(false)
        private set

    fun revealAllIssues() { revealAll = true }

    val visibleNameIssue: String? get() = if (nameTouched || revealAll) nameIssue else null
    val visibleShiftsIssue: String? get() = if (shiftsTouched || revealAll) shiftsIssue else null

    /** Adds a shift on the first weekday without one (or Monday), 9 to 5 with a 30 minute break. */
    fun addShift() {
        val used = shifts.map { it.weekday }.toSet()
        shifts.add(TemplateShiftDraft(weekday = DayOfWeek.entries.firstOrNull { it !in used } ?: DayOfWeek.MONDAY))
        shiftsTouched = true
    }

    fun removeShift(id: String) {
        shifts.removeAll { it.id == id }
        shiftsTouched = true
    }

    fun updateShift(id: String, change: (TemplateShiftDraft) -> TemplateShiftDraft) {
        val i = shifts.indexOfFirst { it.id == id }
        if (i >= 0) shifts[i] = change(shifts[i])
    }

    val nameIssue: String? get() = if (name.isBlank()) "Enter a name, such as Semester 1." else null
    val shiftsIssue: String? get() = if (shifts.isEmpty()) "Add at least one shift." else null

    /** Total paid minutes in the week, for the footer summary. */
    val paidMinutes: Int get() = shifts.sumOf { maxOf(0, it.durationMinutes - it.breakMinutes) }

    val isValid: Boolean get() = nameIssue == null && shiftsIssue == null && shifts.all { it.issue == null }

    /** The template to save, shifts ordered by weekday then start. Null while invalid. */
    fun makeTemplate(): Template? {
        if (!isValid) return null
        val ordered = shifts.mapNotNull { it.shift }.sortedWith(compareBy({ it.weekday }, { it.start }))
        return Template(id, sourceId, name.trim(), if (hasEffectiveFrom) effectiveFrom else null, ordered)
    }

    suspend fun save(repository: Repository): Template {
        val template = makeTemplate() ?: run {
            revealAllIssues()
            throw EditorInvalidException()
        }
        return repository.save(template)
    }

    suspend fun delete(repository: Repository) {
        if (id != 0) repository.deleteTemplate(id)
    }
}

/** The list of default weeks for one source, oldest effective date first (no date counts as the oldest). */
class TemplatesListModel(val sourceId: Int) {
    val templates = mutableStateListOf<Template>()
    var errorMessage by mutableStateOf<String?>(null)

    suspend fun load(repository: Repository) {
        val sorted = repository.templates()
            .filter { it.sourceID == sourceId }
            .sortedWith(compareBy({ it.effectiveFrom ?: LocalDate.of(0, 1, 1) }, { it.id }))
        templates.clear()
        templates.addAll(sorted)
    }

    suspend fun delete(repository: Repository, templateId: Int) {
        repository.deleteTemplate(templateId)
        load(repository)
    }

    companion object {
        fun subtitle(template: Template): String {
            val days = template.shifts.map { it.weekday }.distinct().sorted().joinToString(", ") { EditorLabels.shortWeekday(it) }
            val from = template.effectiveFrom?.let { "From ${EditorLabels.date(it)}" } ?: "Always"
            return "$from · ${template.shifts.size} shifts · $days"
        }
    }
}

// endregion

// region Composables

@Composable
internal fun TemplateEditorForm(model: TemplateEditorModel, onDelete: (() -> Unit)?) {
    EditorSection("Name") {
        EditorTextField("Name", model.name, { model.name = it }, error = model.visibleNameIssue)
    }

    EditorSection(
        null,
        footer = if (model.hasEffectiveFrom) "Used from the week containing this date, until a later default week starts."
        else "Used for every week unless a later default week starts.",
    ) {
        SwitchRow("Starts on a date", model.hasEffectiveFrom, { model.hasEffectiveFrom = it })
        if (model.hasEffectiveFrom) DateField("Effective from", model.effectiveFrom, { model.effectiveFrom = it })
    }

    model.shifts.forEachIndexed { index, draft -> ShiftCard(model, index, draft) }

    EditorSection(
        null,
        footer = if (model.shifts.isEmpty()) null
        else "About ${model.paidMinutes / 60} h ${model.paidMinutes % 60} min paid per week.",
    ) {
        OutlinedButton(onClick = { model.addShift() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AddCircle, contentDescription = null)
            Text("Add shift", Modifier.padding(start = 8.dp))
        }
        EditorFieldMessage(model.visibleShiftsIssue)
    }

    if (!model.isNew && onDelete != null) {
        EditorDeleteSection(
            "Delete default week", "Weeks that used it fall back to an earlier default week, if any.", onDelete,
        )
    }
}

@Composable
private fun ShiftCard(model: TemplateEditorModel, index: Int, draft: TemplateShiftDraft) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${EditorLabels.weekdayName(draft.weekday)} shift", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = { model.removeShift(draft.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove shift ${index + 1}")
                }
            }
            DropdownField(
                "Day", DayOfWeek.entries, draft.weekday,
                { d -> model.updateShift(draft.id) { it.copy(weekday = d) } }, EditorLabels::weekdayName,
            )
            TimeField("Start", draft.start, { t -> model.updateShift(draft.id) { it.copy(start = t) } })
            TimeField("End", draft.end, { t -> model.updateShift(draft.id) { it.copy(end = t) } })
            if (draft.crossesMidnight && draft.start != draft.end) {
                Text(
                    "Finishes next day",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepperRow(
                "Unpaid break: ${draft.breakMinutes} min", draft.breakMinutes,
                { m -> model.updateShift(draft.id) { it.copy(breakMinutes = m) } }, 0..240, step = 5,
                description = "Unpaid break in minutes",
            )
            EditorFieldMessage(draft.issue)
        }
    }
}

/**
 * Edit one default week of shifts for an hourly source, optionally effective from a date. Pass `template = null`
 * to create. [onDeleted] runs after a delete.
 */
@Composable
fun TemplateEditor(
    repository: Repository,
    sourceId: Int,
    onSaved: (Template) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    template: Template? = null,
    onDeleted: (() -> Unit)? = null,
) {
    val model = remember(template?.id, sourceId) { TemplateEditorModel(sourceId, template) }
    val runner = rememberEditorRunner()
    EditorScaffold(
        title = if (model.isNew) "New default week" else "Edit default week",
        canSave = model.isValid, onCancel = onDismiss, modifier = modifier, runner = runner,
        onSave = { runner.run({ model.save(repository) }, onSaved) },
    ) {
        TemplateEditorForm(model, onDelete = { runner.run({ model.delete(repository) }, { onDeleted?.invoke() }) })
    }
}

private sealed interface TemplatesScreen {
    data object List : TemplatesScreen
    data class Edit(val template: Template?) : TemplatesScreen
}

/**
 * Lists every default week of a source (each with its `effectiveFrom`) and opens [TemplateEditor] to add, edit or
 * delete them. Navigation between the list and an editor is internal; [onDismiss] leaves the whole screen.
 */
@Composable
fun TemplatesEditor(
    repository: Repository,
    sourceId: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = remember(sourceId) { TemplatesListModel(sourceId) }
    var screen by remember { mutableStateOf<TemplatesScreen>(TemplatesScreen.List) }
    val runner = rememberEditorRunner()
    // Reload whenever the list is shown again, so a save or delete in the editor is reflected.
    LaunchedEffect(screen) { if (screen == TemplatesScreen.List) model.load(repository) }

    when (val current = screen) {
        is TemplatesScreen.Edit -> {
            BackHandler { screen = TemplatesScreen.List }
            TemplateEditor(
                repository, sourceId,
                onSaved = { screen = TemplatesScreen.List },
                onDismiss = { screen = TemplatesScreen.List },
                modifier = modifier, template = current.template,
                onDeleted = { screen = TemplatesScreen.List },
            )
        }
        TemplatesScreen.List -> EditorScaffold(
            title = "Default weeks", canSave = true, saveLabel = "Done",
            onCancel = onDismiss, onSave = onDismiss, modifier = modifier, runner = runner,
        ) {
            TemplatesList(
                model, onAdd = { screen = TemplatesScreen.Edit(null) },
                onOpen = { screen = TemplatesScreen.Edit(it) },
                onDelete = { id -> runner.run({ model.delete(repository, id) }, {}) },
            )
        }
    }
}

@Composable
internal fun TemplatesList(
    model: TemplatesListModel,
    onAdd: () -> Unit,
    onOpen: (Template) -> Unit,
    onDelete: (Int) -> Unit,
) {
    if (model.templates.isEmpty()) {
        Text("No default week", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add the shifts you usually work. They fill future weeks in the forecast.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    model.templates.forEach { template ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val subtitle = TemplatesListModel.subtitle(template)
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) { onOpen(template) }
                        .padding(vertical = 12.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${template.name}. $subtitle. Edit" },
                ) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onDelete(template.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${template.name}")
                }
            }
        }
    }
    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AddCircle, contentDescription = null)
        Text("Add default week", Modifier.padding(start = 8.dp))
    }
}

// endregion

@Preview(name = "Default week (demo)", showBackground = true, heightDp = 1500)
@Composable
private fun TemplateEditorPreview() {
    BeaverTheme {
        val model = remember { TemplateEditorModel(DemoData.SOURCE_ID, DemoData.template) }
        EditorScaffold("Edit default week", canSave = true, onCancel = {}, onSave = {}) {
            TemplateEditorForm(model, onDelete = {})
        }
    }
}

@Preview(name = "Default weeks list (demo)", showBackground = true)
@Composable
private fun TemplatesListPreview() {
    BeaverTheme {
        val model = remember { TemplatesListModel(DemoData.SOURCE_ID).also { it.templates.add(DemoData.template) } }
        EditorScaffold("Default weeks", canSave = true, saveLabel = "Done", onCancel = {}, onSave = {}) {
            TemplatesList(model, {}, {}, {})
        }
    }
}
