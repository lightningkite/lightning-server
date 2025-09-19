package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.EmailAddress
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.terraformJsonObject
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public data class LambdaInvocationAlarmThresholds(
    val threshold: Int,
    val period: Duration = 1.minutes,
    val evaluationPeriods: Int = 1,
    val dataPointsToAlarm: Int = 1,
) {
}

public data class LambdaDurationAlarmThresholds(
    val threshold: Duration,
    val period: Duration = 1.minutes,
    val statistic: LambdaAlarmThresholdsStatistic = LambdaAlarmThresholdsStatistic.Sum,
    val evaluationPeriods: Int = 1,
    val dataPointsToAlarm: Int = 1,
) {
}

public enum class LambdaAlarmThresholdsStatistic {
    Average,
    Sum,
}

