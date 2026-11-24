@file:UseSerializers(LocalDateSerializer::class, WeekdaySerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate

/** When an income source is paid and which work period each payday covers. */
@Serializable(with = PayScheduleSerializer::class)
sealed interface PaySchedule {
    /** Paid every week on [payWeekday]; [lagWeeks] 1 means the week just finished is paid. */
    data class Weekly(val payWeekday: DayOfWeek, val lagWeeks: Int) : PaySchedule

    /** Paid every 14 days from [anchorPayday] in both directions. */
    data class Fortnightly(val anchorPayday: LocalDate, val lagWeeks: Int) : PaySchedule

    /** Paid on [dayOfMonth] (clamped to the month end). Fixed-amount sources only. */
    data class Monthly(val dayOfMonth: Int) : PaySchedule

    val frequency: PayFrequency
        get() = when (this) {
            is Weekly -> PayFrequency.weekly
            is Fortnightly -> PayFrequency.fortnightly
            is Monthly -> PayFrequency.monthly
        }

    /** Paydays inside the inclusive range, in order. */
    fun paydays(range: DateRange): List<LocalDate> {
        if (range.isEmpty) return emptyList()
        val out = mutableListOf<LocalDate>()
        when (this) {
            is Weekly -> {
                val offset = Math.floorMod(payWeekday.value - range.start.dayOfWeek.value, 7)
                var d = range.start.plusDays(offset.toLong())
                while (d <= range.end) {
                    out.add(d)
                    d = d.plusDays(7)
                }
            }
            is Fortnightly -> {
                val k = -Math.floorDiv(-anchorPayday.daysUntil(range.start), 14) // ceil((start - anchor) / 14)
                var d = anchorPayday.plusDays(k * 14L)
                while (d <= range.end) {
                    out.add(d)
                    d = d.plusDays(14)
                }
            }
            is Monthly -> {
                var month = LocalDate.of(range.start.year, range.start.month, 1)
                while (month <= range.end) {
                    val day = minOf(maxOf(dayOfMonth, 1), month.lengthOfMonth())
                    val d = LocalDate.of(month.year, month.month, day)
                    if (d in range) out.add(d)
                    month = month.plusMonths(1)
                }
            }
        }
        return out
    }

    /** The Mon-Sun work period (7 days weekly, 14 days fortnightly) paid on [payday]. Null for monthly schedules. */
    fun workPeriod(payday: LocalDate): DateRange? = when (this) {
        is Weekly -> {
            val sunday = payday.plusDays(-(lagWeeks - 1) * 7L).previousSunday
            DateRange(sunday.minusDays(6), sunday)
        }
        is Fortnightly -> {
            val sunday = payday.plusDays(-(lagWeeks - 1) * 7L).previousSunday
            DateRange(sunday.minusDays(13), sunday)
        }
        is Monthly -> null
    }

    /** The payday that pays work done on [date]. Null for monthly schedules. */
    fun paydayForWorkDate(date: LocalDate): LocalDate? = when (this) {
        is Weekly -> date.sundayOfWeek.plusDays(payWeekday.value + (lagWeeks - 1) * 7L)
        is Fortnightly -> {
            val anchorSunday = anchorPayday.plusDays(-(lagWeeks - 1) * 7L).previousSunday
            val firstDay = anchorSunday.minusDays(13)
            val k = Math.floorDiv(firstDay.daysUntil(date), 14)
            anchorPayday.plusDays(k * 14L)
        }
        is Monthly -> null
    }
}

/** Swift shape: `{"weekly":{"payWeekday":3,"lagWeeks":1}}`, `{"fortnightly":{"anchorPayday":"...","lagWeeks":1}}`, `{"monthly":{"dayOfMonth":31}}`. */
internal object PayScheduleSerializer : SwiftEnumSerializer<PaySchedule>("PaySchedule") {
    override fun encodeCase(value: PaySchedule): Pair<String, JsonObject> = when (value) {
        is PaySchedule.Weekly -> "weekly" to payload("payWeekday" to value.payWeekday.value, "lagWeeks" to value.lagWeeks)
        is PaySchedule.Fortnightly -> "fortnightly" to JsonObject(
            mapOf("anchorPayday" to JsonPrimitive(value.anchorPayday.toString()), "lagWeeks" to JsonPrimitive(value.lagWeeks))
        )
        is PaySchedule.Monthly -> "monthly" to payload("dayOfMonth" to value.dayOfMonth)
    }

    override fun decodeCase(name: String, payload: JsonObject): PaySchedule = when (name) {
        "weekly" -> PaySchedule.Weekly(DayOfWeek.of(payload.intAt("payWeekday")), payload.intAt("lagWeeks"))
        "fortnightly" -> PaySchedule.Fortnightly(
            parseIsoDate(payload.stringAt("anchorPayday")) ?: throw SerializationException("Invalid anchorPayday"),
            payload.intAt("lagWeeks"),
        )
        "monthly" -> PaySchedule.Monthly(payload.intAt("dayOfMonth"))
        else -> throw SerializationException("Unknown PaySchedule $name")
    }
}
