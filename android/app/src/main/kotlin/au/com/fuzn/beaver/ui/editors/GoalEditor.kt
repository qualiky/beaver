package au.com.fuzn.beaver.ui.editors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.LocalDate

/**
 * State for the goal editor. For a new goal, "Already saved" becomes one [Deposit] dated [today]; "Make primary"
 * writes the `primaryGoalId` setting.
 */
class GoalEditorModel(
    goal: Goal? = null,
    isPrimary: Boolean = false,
    isFirstGoal: Boolean = true,
    today: LocalDate = LocalDate.now(),
) {
    enum class Field { Name, Target, Deadline, Saved }

    val isNew: Boolean = goal == null
    val id: Int = goal?.id ?: 0
    var today: LocalDate = today
        private set
    private val originalDeadline: LocalDate? = goal?.deadline
    private val wasPrimary: Boolean = isPrimary

    val touches = FieldTouches<Field>()
    fun touch(field: Field) = touches.touch(field)
    fun revealAllIssues() { touches.revealAll = true }

    private var nameState by mutableStateOf(goal?.name ?: "")
    var name: String
        get() = nameState
        set(v) { nameState = v; touch(Field.Name) }

    private var targetState by mutableStateOf(goal?.let { MoneyParser.text(it.target.cents) } ?: "")
    var targetText: String
        get() = targetState
        set(v) { targetState = v; touch(Field.Target) }

    var hasDeadline by mutableStateOf(goal?.deadline != null)

    private var deadlineState by mutableStateOf(goal?.deadline ?: today.plusMonths(6))
    var deadline: LocalDate
        get() = deadlineState
        set(v) { deadlineState = v; touch(Field.Deadline) }

    /** Amount already saved. Only used for a new goal: it becomes an initial deposit. */
    private var savedState by mutableStateOf("")
    var savedText: String
        get() = savedState
        set(v) { savedState = v; touch(Field.Saved) }

    var makePrimary by mutableStateOf(if (goal == null) isFirstGoal else isPrimary)

    /** Loads whether this goal is primary and whether other goals exist, using the settings time zone for today. */
    suspend fun load(repository: Repository) {
        val settings = repository.settings()
        val newToday = LocalDate.now(settings.timeZone)
        if (!touches.shows(Field.Deadline) && !hasDeadline) deadlineState = newToday.plusMonths(6)
        today = newToday
        makePrimary = if (isNew) repository.goals().isEmpty() else settings.primaryGoalId == id
    }

    fun visibleIssue(field: Field): String? = if (touches.shows(field)) issue(field) else null

    fun issue(field: Field): String? = when (field) {
        Field.Name -> if (name.isBlank()) "Enter a name, such as Course fees." else null
        Field.Target -> {
            val cents = MoneyParser.cents(targetText)
            if (cents == null) "Enter a target such as 6,000.00."
            else if (cents <= 0) "The target must be more than zero."
            else null
        }
        Field.Deadline -> when {
            !hasDeadline -> null
            // An unchanged existing deadline is allowed even if it has passed.
            deadline == originalDeadline -> null
            deadline > today -> null
            else -> "Choose a date after today."
        }
        Field.Saved ->
            if (!isNew || savedText.isBlank()) null
            else if (MoneyParser.cents(savedText) == null) "Enter an amount such as 250.00, or leave blank."
            else null
    }

    val isValid: Boolean get() = Field.entries.all { issue(it) == null }

    /** Initial deposit in cents, for a new goal with a positive saved amount. */
    val initialDepositCents: Long?
        get() = if (isNew) MoneyParser.cents(savedText)?.takeIf { it > 0 } else null

    fun makeGoal(): Goal? {
        if (!isValid) return null
        val target = MoneyParser.cents(targetText) ?: return null
        return Goal(id, name.trim(), Money(target), if (hasDeadline) deadline else null)
    }

    /** Saves the goal, then its initial deposit (new goals only), then the primary-goal setting. */
    suspend fun save(repository: Repository): Goal {
        val goal = makeGoal() ?: run {
            revealAllIssues()
            throw EditorInvalidException()
        }
        val stored = repository.save(goal)
        initialDepositCents?.let { cents ->
            repository.save(Deposit(goalID = stored.id, date = today, amount = Money(cents), note = "Already saved"))
        }
        if (makePrimary) repository.setPrimaryGoalId(stored.id)
        else if (wasPrimary) repository.setPrimaryGoalId(null)
        return stored
    }

    suspend fun delete(repository: Repository) {
        if (id == 0) return
        repository.deleteGoal(id)
        if (repository.settings().primaryGoalId == id) repository.setPrimaryGoalId(null)
    }
}

@Composable
internal fun GoalEditorForm(model: GoalEditorModel, currencyCode: String, onDelete: (() -> Unit)?) {
    EditorSection("Goal") {
        EditorTextField(
            "Name", model.name, { model.name = it },
            error = model.visibleIssue(GoalEditorModel.Field.Name), capitalization = KeyboardCapitalization.Words,
        )
        MoneyField(
            "Target", model.targetText, { model.targetText = it },
            currencyCode = currencyCode, error = model.visibleIssue(GoalEditorModel.Field.Target),
        )
    }

    EditorSection(null, footer = "Without a deadline the forecast looks 26 weeks ahead.") {
        SwitchRow("Deadline", model.hasDeadline, { model.hasDeadline = it })
        if (model.hasDeadline) {
            DateField("Save by", model.deadline, { model.deadline = it })
            EditorFieldMessage(model.visibleIssue(GoalEditorModel.Field.Deadline))
        }
    }

    if (model.isNew) {
        EditorSection(
            null,
            footer = "Counted as a first deposit towards this goal. Leave blank if you're starting from zero.",
        ) {
            MoneyField(
                "Already saved", model.savedText, { model.savedText = it },
                currencyCode = currencyCode, error = model.visibleIssue(GoalEditorModel.Field.Saved),
            )
        }
    }

    EditorSection(null, footer = "The primary goal drives the Week tab.") {
        SwitchRow("Make primary goal", model.makePrimary, { model.makePrimary = it })
    }

    if (!model.isNew && onDelete != null) {
        EditorDeleteSection("Delete goal", "This also deletes its deposits. It can't be undone.", onDelete)
    }
}

/**
 * Create or edit a savings goal. For a new goal, "Already saved" becomes an initial deposit. [onSaved] receives the
 * stored goal; [onDeleted] runs after a delete.
 */
@Composable
fun GoalEditor(
    repository: Repository,
    onSaved: (Goal) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    goal: Goal? = null,
    currencyCode: String = "AUD",
    onDeleted: (() -> Unit)? = null,
) {
    val model = remember(goal?.id) { GoalEditorModel(goal) }
    val runner = rememberEditorRunner()
    LaunchedEffect(model) { runCatching { model.load(repository) } }
    EditorScaffold(
        title = if (model.isNew) "New goal" else "Edit goal",
        canSave = model.isValid, onCancel = onDismiss, modifier = modifier, runner = runner,
        onSave = { runner.run({ model.save(repository) }, onSaved) },
    ) {
        GoalEditorForm(model, currencyCode, onDelete = { runner.run({ model.delete(repository) }, { onDeleted?.invoke() }) })
    }
}

@Preview(name = "New goal", showBackground = true, heightDp = 900)
@Composable
private fun GoalEditorNewPreview() {
    BeaverTheme {
        val model = remember { GoalEditorModel(today = LocalDate.of(2026, 11, 23)) }
        EditorScaffold("New goal", canSave = false, onCancel = {}, onSave = {}) {
            GoalEditorForm(model, "AUD", null)
        }
    }
}

@Preview(name = "Demo goal", showBackground = true, heightDp = 900)
@Composable
private fun GoalEditorDemoPreview() {
    BeaverTheme {
        val model = remember { GoalEditorModel(DemoData.goal, isPrimary = true, today = DemoData.referenceToday) }
        EditorScaffold("Edit goal", canSave = true, onCancel = {}, onSave = {}) {
            GoalEditorForm(model, "AUD", onDelete = {})
        }
    }
}
