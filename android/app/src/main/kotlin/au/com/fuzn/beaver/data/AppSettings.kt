package au.com.fuzn.beaver.data

import au.com.fuzn.beaver.core.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId

/** Typed view of the `setting` table. Missing or unreadable keys take these defaults. */
data class AppSettings(
    val currency: String = "AUD",
    val timeZoneId: String = ZoneId.systemDefault().id,
    val onboardingDone: Boolean = false,
    val lastExportAt: Instant? = null,
    val primaryGoalId: Int? = null,
    val livingBudget: Money = Money.ZERO,
) {
    val timeZone: ZoneId get() = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }

    object Key {
        const val CURRENCY = "currency"
        const val TIME_ZONE = "timeZone"
        const val ONBOARDING_DONE = "onboardingDone"
        const val LAST_EXPORT_AT = "lastExportAt"
        const val PRIMARY_GOAL_ID = "primaryGoalId"
        const val LIVING_BUDGET_CENTS = "livingBudgetCents"
    }

    companion object {
        fun fromRows(rows: List<SettingEntity>): AppSettings {
            fun primitive(key: String): JsonPrimitive? {
                val row = rows.firstOrNull { it.key == key } ?: return null
                return runCatching { Json.parseToJsonElement(row.value) as? JsonPrimitive }.getOrNull()
            }

            fun string(key: String): String? = primitive(key)?.takeIf { it.isString }?.contentOrNull
            fun int(key: String): Int? = primitive(key)?.takeIf { !it.isString }?.intOrNull
            val defaults = AppSettings()
            return AppSettings(
                currency = string(Key.CURRENCY) ?: defaults.currency,
                timeZoneId = string(Key.TIME_ZONE) ?: defaults.timeZoneId,
                onboardingDone = primitive(Key.ONBOARDING_DONE)?.takeIf { !it.isString }?.booleanOrNull ?: false,
                lastExportAt = string(Key.LAST_EXPORT_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() },
                primaryGoalId = int(Key.PRIMARY_GOAL_ID),
                livingBudget = primitive(Key.LIVING_BUDGET_CENTS)?.takeIf { !it.isString }?.longOrNull?.let { Money(it) }
                    ?: Money.ZERO,
            )
        }
    }
}
