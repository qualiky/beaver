package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable

/** An amount of money in integer minor units (cents). Never uses floating point. Serialised as a bare integer. */
@JvmInline
@Serializable
value class Money(val cents: Long) : Comparable<Money> {
    val isNegative: Boolean get() = cents < 0

    operator fun plus(other: Money): Money = Money(cents + other.cents)
    operator fun minus(other: Money): Money = Money(cents - other.cents)
    operator fun unaryMinus(): Money = Money(-cents)
    operator fun times(factor: Int): Money = Money(cents * factor)
    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)
    override fun toString(): String = "Money($cents)"

    companion object {
        val ZERO = Money(0)

        /** Whole dollars plus optional cents, e.g. `Money.dollars(30, 50)` is $30.50. */
        fun dollars(dollars: Int, cents: Int = 0): Money =
            Money(dollars * 100L + (if (dollars < 0) -cents else cents))
    }
}

/** Sum of all amounts. */
fun Iterable<Money>.total(): Money {
    var sum = 0L
    for (m in this) sum += m.cents
    return Money(sum)
}

/** Exact integer helpers used across the engine. */
internal object IntMath {
    /** `n / d` rounded half up (toward positive infinity on ties). `d` must be positive. */
    fun roundHalfUp(n: Long, d: Long): Long = Math.floorDiv(2 * n + d, 2 * d)

    fun roundHalfUp(n: Int, d: Int): Int = roundHalfUp(n.toLong(), d.toLong()).toInt()
}
