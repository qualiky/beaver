@file:UseSerializers(LocalDateSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

/** A savings goal with an optional deadline. */
@Serializable
data class Goal(
    val id: Int = 0,
    val name: String,
    val target: Money,
    val deadline: LocalDate? = null,
) {
    /** Sum of deposits logged against this goal. */
    fun saved(deposits: List<Deposit>): Money = deposits.filter { it.goalID == id }.map { it.amount }.total()

    /** Progress in basis points (10000 is complete), clamped to 0..10000. */
    fun progressBasisPoints(deposits: List<Deposit>): Int {
        if (target.cents <= 0) return 10_000
        return minOf(10_000L, maxOf(0L, saved(deposits).cents * 10_000 / target.cents)).toInt()
    }
}

/** Money put towards a goal. */
@Serializable
data class Deposit(
    val id: Int = 0,
    val goalID: Int,
    val date: LocalDate,
    val amount: Money,
    val note: String = "",
)
