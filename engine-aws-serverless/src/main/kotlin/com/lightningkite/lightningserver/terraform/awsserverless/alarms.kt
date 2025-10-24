package com.lightningkite.lightningserver.terraform.awsserverless

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public data class LambdaAlarm(
    val metric: LambdaAlarmMetric,
    val statistic: LambdaAlarmStatistic,
    val threshold: Long,
    val period: Duration,
    val evaluationPeriods: Int,
    val dataPointsToAlarm: Int,
    val description: String,
)

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

