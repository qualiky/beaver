package au.com.fuzn.beaver.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Dates are java.time.LocalDate and times java.time.LocalTime: wall-clock only, never instants or zones.
// Weeks start on Monday; DayOfWeek.value is the ISO weekday (Monday 1 ... Sunday 7), as in the Swift engine.

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** `YYYY-MM-DD`, matching the Swift `LocalDate` coding. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate {
        val s = decoder.decodeString()
        return parseIsoDate(s) ?: throw SerializationException("Invalid date $s")
    }
}

/** `HH:mm`, matching the Swift `LocalTime` coding. */
object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.format(timeFormat))
    override fun deserialize(decoder: Decoder): LocalTime {
        val s = decoder.decodeString()
        return parseIsoTime(s) ?: throw SerializationException("Invalid time $s")
    }
}

/** ISO weekday number (Monday 1 ... Sunday 7), matching the Swift `Weekday` raw value. */
object WeekdaySerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Weekday", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: DayOfWeek) = encoder.encodeInt(value.value)
    override fun deserialize(decoder: Decoder): DayOfWeek {
        val n = decoder.decodeInt()
        if (n !in 1..7) throw SerializationException("Invalid weekday $n")
        return DayOfWeek.of(n)
    }
}

/** Strict `YYYY-MM-DD` parse (4-2-2 digits, real calendar date). Null if malformed. */
fun parseIsoDate(s: String): LocalDate? {
    val p = s.split("-")
    if (p.size != 3 || p[0].length != 4 || p[1].length != 2 || p[2].length != 2) return null
    if (!p.all { part -> part.all { it in '0'..'9' } }) return null
    return try {
        LocalDate.of(p[0].toInt(), p[1].toInt(), p[2].toInt())
    } catch (_: java.time.DateTimeException) {
        null
    }
}

/** Strict `HH:mm` parse. Null if malformed (so `24:00` is rejected). */
fun parseIsoTime(s: String): LocalTime? {
    val p = s.split(":")
    if (p.size != 2 || p[0].length != 2 || p[1].length != 2) return null
    if (!p.all { part -> part.all { it in '0'..'9' } }) return null
    val h = p[0].toInt()
    val m = p[1].toInt()
    if (h !in 0..23 || m !in 0..59) return null
    return LocalTime.of(h, m)
}

val DayOfWeek.isWeekend: Boolean get() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

/** Minutes since midnight, `0..<1440`. */
val LocalTime.minutesSinceMidnight: Int get() = hour * 60 + minute

fun localTimeOfMinutes(minutes: Int): LocalTime {
    val n = Math.floorMod(minutes, 1440)
    return LocalTime.of(n / 60, n % 60)
}

/** The Monday that starts this date's Monday-to-Sunday week. */
val LocalDate.mondayOfWeek: LocalDate get() = minusDays((dayOfWeek.value - 1).toLong())

/** The Sunday that ends this date's week. */
val LocalDate.sundayOfWeek: LocalDate get() = plusDays((7 - dayOfWeek.value).toLong())

/** The last Sunday strictly before this date. */
val LocalDate.previousSunday: LocalDate get() = minusDays(dayOfWeek.value.toLong())

/** The Monday-to-Sunday week containing this date. */
val LocalDate.week: DateRange get() = DateRange(mondayOfWeek, sundayOfWeek)

/** Signed number of days from this date to `other`. */
fun LocalDate.daysUntil(other: LocalDate): Int = (other.toEpochDay() - toEpochDay()).toInt()

/** An inclusive range of dates. An empty range (`end < start`) is allowed and contains nothing. */
@Serializable
data class DateRange(
    @Serializable(with = LocalDateSerializer::class) val start: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val end: LocalDate,
) {
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= end
    val isEmpty: Boolean get() = end < start
    val dayCount: Int get() = if (isEmpty) 0 else start.daysUntil(end) + 1

    /** Every date in the range, in order. */
    val dates: List<LocalDate> get() = (0 until dayCount).map { start.plusDays(it.toLong()) }
}
