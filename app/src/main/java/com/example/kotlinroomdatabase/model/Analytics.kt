package com.example.kotlinroomdatabase.model

import kotlin.math.roundToInt

enum class AnalyticsMetric { RATING, ATTENDANCE }
enum class AnalyticsOrder { LOWEST, HIGHEST, NAME }

data class AnalyticsEntry(
    val id: Int,
    val name: String,
    val rating: Double?,
    val attendance: Double?,
    val score: Double? = null,
    val maximum: Double? = null,
    val attended: Int? = null,
    val sessions: Int? = null
) {
    fun value(metric: AnalyticsMetric): Double? =
        (if (metric == AnalyticsMetric.RATING) rating else attendance)
            ?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
}

data class AnalyticsSnapshot(val entries: List<AnalyticsEntry>, val period: String)

object AnalyticsMath {
    fun percent(numerator: Double?, denominator: Double?): Double? =
        if (numerator != null && denominator != null && numerator.isFinite() &&
            denominator.isFinite() && denominator > 0) (numerator * 100 / denominator).coerceIn(0.0, 100.0)
        else null

    fun average(entries: List<AnalyticsEntry>, metric: AnalyticsMetric): Double? =
        entries.mapNotNull { it.value(metric) }.takeIf { it.isNotEmpty() }?.average()

    fun sorted(entries: List<AnalyticsEntry>, metric: AnalyticsMetric, order: AnalyticsOrder): List<AnalyticsEntry> =
        entries.sortedWith { a, b ->
            val left = a.value(metric)
            val right = b.value(metric)
            when {
                order == AnalyticsOrder.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                left == null && right == null -> a.name.compareTo(b.name, ignoreCase = true)
                left == null -> 1
                right == null -> -1
                order == AnalyticsOrder.HIGHEST -> right.compareTo(left)
                else -> left.compareTo(right)
            }
        }

    fun format(value: Double?): String = value?.takeIf { it.isFinite() }
        ?.coerceIn(0.0, 100.0)?.roundToInt()?.let { "$it%" } ?: "—"
}
