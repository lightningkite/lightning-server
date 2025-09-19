package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.DataSize
import com.lightningkite.DataSize.Companion.gibibytes
import com.lightningkite.EmailAddress
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.cache.dynamodb.awsDynamoDb
import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.terraform.direct
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

//fun <S: ServerBuilder> S.deploymentAwsLambda(
//    projectPrefix: String,
//    deploymentTag: String,
//    storageBucket: String,
//    storageBucketPathOverride: String? = null,
//    region: Region,
//    handlerFullyQualifiedName: String,
//    displayName: String,
//    debug: Boolean,
//    emergencyContact: EmailAddress,
//    snapStart: Boolean = true,
//    timeout: Duration = 30.seconds,
//    memory: DataSize = 1.gibibytes,
//    emergencyInvocations: LambdaInvocationAlarmThresholds = LambdaInvocationAlarmThresholds(threshold = 150),
//    emergencyCompute: LambdaDurationAlarmThresholds = LambdaDurationAlarmThresholds(threshold = 3.minutes),
//    panicInvocations: LambdaInvocationAlarmThresholds = LambdaInvocationAlarmThresholds(threshold = 450),
//    panicCompute: LambdaDurationAlarmThresholds = LambdaDurationAlarmThresholds(threshold = 5.minutes),
//    setSettings: context(TerraformAwsServerlessBuilder<S>) S.()->Unit,
//    additional: TerraformAwsServerlessBuilder<S>.()->Unit = {}
//) = TerraformAwsServerlessBuilder(this, TerraformAwsServerlessBuilder.Config(
//    projectPrefix = projectPrefix,
//    deploymentTag = deploymentTag,
//    storageBucket = storageBucket,
//    storageBucketPathOverride = storageBucketPathOverride,
//    region = region,
//    handlerFullyQualifiedName = handlerFullyQualifiedName,
//    displayName = displayName,
//    debug = debug,
//    emergencyContact = emergencyContact,
//    snapStart = snapStart,
//    timeout = timeout,
//    memory = memory,
//    emergencyInvocations = emergencyInvocations,
//    emergencyCompute = emergencyCompute,
//    panicInvocations = panicInvocations,
//    panicCompute = panicCompute,
//)
//).apply {
//    settings { setSettings() }
//    additional()
//}.write(File("build/terraform/$projectPrefix").also { it.mkdirs() })