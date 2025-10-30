package com.lightningkite.lightningserver.terraform.awsserverless

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

public data class LambdaAlarm(
    val metric: LambdaAlarmMetric,
    val statistic: LambdaAlarmStatistic,
    val threshold: Long,
    val period: Duration,
    val evaluationPeriods: Int,
    val dataPointsToAlarm: Int,
    val description: String,
) {
    public companion object {
        private val defaultFlexTable = mapOf(
            7.days to 1.0,
            1.days to 2.0,
            4.hours to 4.0,
            1.hours to 8.0,
            15.minutes to 16.0,
            5.minutes to 32.0
        )
        public fun defaultSpendAlarms(
            computeSecondsPerMonth: Duration,
            description: String,
            flexTable: Map<Duration, Double> = defaultFlexTable,
        ): List<LambdaAlarm> = flexTable.entries.map {
            LambdaDurationAlarm(
                threshold = computeSecondsPerMonth * (it.key / 30.days) * it.value,
                period = it.key,
                statistic = LambdaAlarmStatistic.Sum,
                description = "$description (${it.key})",
            )
        }
    }
}

public fun LambdaInvocationAlarm(
    statistic: LambdaAlarmStatistic = LambdaAlarmStatistic.Sum,
    threshold: Long,
    period: Duration = 1.minutes,
    evaluationPeriods: Int = 1,
    dataPointsToAlarm: Int = 1,
    description: String,
):LambdaAlarm = LambdaAlarm(
    LambdaAlarmMetric.Invocations,
    statistic,
    threshold,
    period,
    evaluationPeriods,
    dataPointsToAlarm,
    description,
)

public fun LambdaDurationAlarm(
    threshold: Duration,
    period: Duration = 1.minutes,
    statistic: LambdaAlarmStatistic = LambdaAlarmStatistic.Sum,
    evaluationPeriods: Int = 1,
    dataPointsToAlarm: Int = 1,
    description: String,
) : LambdaAlarm = LambdaAlarm(
    LambdaAlarmMetric.Duration,
    statistic,
    threshold.inWholeMilliseconds,
    period,
    evaluationPeriods,
    dataPointsToAlarm,
    description,
)

public enum class LambdaAlarmStatistic {
    Average,
    Sum,
}

public enum class LambdaAlarmMetric() {
    Invocations,
    Duration,
}

