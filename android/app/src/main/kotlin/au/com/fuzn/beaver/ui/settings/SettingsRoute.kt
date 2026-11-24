package au.com.fuzn.beaver.ui.settings

/**
 * Where Settings is showing. Saved across rotation as a plain string (see [encode] and [parse]), because there is
 * no navigation library. [List] is the root; every other route is one level below it.
 */
sealed interface SettingsRoute {
    data object List : SettingsRoute
    data object SourceNew : SettingsRoute
    data class Source(val id: Int) : SettingsRoute
    data class Templates(val sourceId: Int) : SettingsRoute
    data object CardNew : SettingsRoute
    data class Card(val id: Int) : SettingsRoute
    data class Table(val id: String) : SettingsRoute
    data object BillNew : SettingsRoute
    data class Bill(val id: Int) : SettingsRoute
    data object GoalNew : SettingsRoute
    data class Goal(val id: Int) : SettingsRoute
    data object Limits : SettingsRoute
    data object TimeZone : SettingsRoute
    data object Setup : SettingsRoute

    fun encode(): String = when (this) {
        List -> "list"
        SourceNew -> "source:new"
        is Source -> "source:$id"
        is Templates -> "templates:$sourceId"
        CardNew -> "card:new"
        is Card -> "card:$id"
        is Table -> "table:$id"
        BillNew -> "bill:new"
        is Bill -> "bill:$id"
        GoalNew -> "goal:new"
        is Goal -> "goal:$id"
        Limits -> "limits"
        TimeZone -> "timezone"
        Setup -> "setup"
    }

    companion object {
        /** The route for [text], or [List] if it is not recognised. */
        fun parse(text: String): SettingsRoute {
            val kind = text.substringBefore(':')
            val arg = text.substringAfter(':', "")
            val id = arg.toIntOrNull()
            return when (kind) {
                "source" -> if (arg == "new") SourceNew else id?.let(::Source)
                "templates" -> id?.let(::Templates)
                "card" -> if (arg == "new") CardNew else id?.let(::Card)
                "table" -> arg.takeIf { it.isNotEmpty() }?.let(::Table)
                "bill" -> if (arg == "new") BillNew else id?.let(::Bill)
                "goal" -> if (arg == "new") GoalNew else id?.let(::Goal)
                "limits" -> Limits
                "timezone" -> TimeZone
                "setup" -> Setup
                else -> null
            } ?: List
        }
    }
}
