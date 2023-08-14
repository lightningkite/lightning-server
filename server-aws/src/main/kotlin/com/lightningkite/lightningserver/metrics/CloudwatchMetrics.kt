package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.Dimension
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit

class CloudwatchMetrics(
    override val settings: MetricSettings,
    val namespace: String,
    val region: Region,
    val credentialProvider: AwsCredentialsProvider,
) : Metrics {
    companion object {
        init {
            MetricSettings.register("cloudwatch") {
                Regex("""cloudwatch:\/\/((?:(?<user>[a-zA-Z0-9+\/]+):(?<password>[a-zA-Z0-9+\/]+)@)?(?<region>[a-zA-Z0-9-]+))/(?<namespace>[^?]+)""").matchEntire(
                    it.url
                )?.let { match ->
                    val user = match.groups["user"]?.value ?: ""
                    val password = match.groups["password"]?.value ?: ""
                    val namespace = match.groups["namespace"]?.value ?: generalSettings().projectName
                    val region = Region.of(match.groups["region"]!!.value.lowercase())
                    CloudwatchMetrics(
                        it,
                        namespace,
                        region,
                        if (user.isNotBlank() && password.isNotBlank()) {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        } else DefaultCredentialsProvider.create(),
                    )
                }
                    ?: throw IllegalStateException("Invalid CloudWatch metrics URL. The URL should match the pattern: cloudwatch://[user]:[password]@[region]/[namespace]")
            }
        }
    }

    val cw = CloudWatchAsyncClient.builder()
        .region(region)
        .credentialsProvider(credentialProvider)
        .build()

    override suspend fun report(events: List<MetricEvent>) {
        events.chunked(1000).forEach { events ->
            cw.putMetricData {
                it.metricData(events.flatMap {
                    listOf(
                        MetricDatum.builder()
                            .metricName(it.metricType.name)
                            .unit(
                                when (it.metricType.unit) {
                                    MetricUnit.Seconds -> StandardUnit.SECONDS
                                    MetricUnit.Microseconds -> StandardUnit.MICROSECONDS
                                    MetricUnit.Milliseconds -> StandardUnit.MILLISECONDS
                                    MetricUnit.Bytes -> StandardUnit.BYTES
                                    MetricUnit.Kilobytes -> StandardUnit.KILOBYTES
                                    MetricUnit.Megabytes -> StandardUnit.MEGABYTES
                                    MetricUnit.Gigabytes -> StandardUnit.GIGABYTES
                                    MetricUnit.Terabytes -> StandardUnit.TERABYTES
                                    MetricUnit.Bits -> StandardUnit.BITS
                                    MetricUnit.Kilobits -> StandardUnit.KILOBITS
                                    MetricUnit.Megabits -> StandardUnit.MEGABITS
                                    MetricUnit.Gigabits -> StandardUnit.GIGABITS
                                    MetricUnit.Terabits -> StandardUnit.TERABITS
                                    MetricUnit.Percent -> StandardUnit.PERCENT
                                    MetricUnit.Count -> StandardUnit.COUNT
                                    MetricUnit.BytesPerSecond -> StandardUnit.BYTES_SECOND
                                    MetricUnit.KilobytesPerSecond -> StandardUnit.KILOBYTES_SECOND
                                    MetricUnit.MegabytesPerSecond -> StandardUnit.MEGABYTES_SECOND
                                    MetricUnit.GigabytesPerSecond -> StandardUnit.GIGABYTES_SECOND
                                    MetricUnit.TerabytesPerSecond -> StandardUnit.TERABYTES_SECOND
                                    MetricUnit.BitsPerSecond -> StandardUnit.BITS_SECOND
                                    MetricUnit.KilobitsPerSecond -> StandardUnit.KILOBITS_SECOND
                                    MetricUnit.MegabitsPerSecond -> StandardUnit.MEGABITS_SECOND
                                    MetricUnit.GigabitsPerSecond -> StandardUnit.GIGABITS_SECOND
                                    MetricUnit.TerabitsPerSecond -> StandardUnit.TERABITS_SECOND
                                    MetricUnit.CountPerSecond -> StandardUnit.COUNT_SECOND
                                    MetricUnit.Other -> StandardUnit.NONE
                                }
                            )
                            .timestamp(it.time)
                            .value(it.value)
                            .dimensions(
                                Dimension.builder().name("Entry Point").value(it.entryPoint).build()
                            )
                            .build(),
                        MetricDatum.builder()
                            .metricName(it.metricType.name)
                            .unit(
                                when (it.metricType.unit) {
                                    MetricUnit.Seconds -> StandardUnit.SECONDS
                                    MetricUnit.Microseconds -> StandardUnit.MICROSECONDS
                                    MetricUnit.Milliseconds -> StandardUnit.MILLISECONDS
                                    MetricUnit.Bytes -> StandardUnit.BYTES
                                    MetricUnit.Kilobytes -> StandardUnit.KILOBYTES
                                    MetricUnit.Megabytes -> StandardUnit.MEGABYTES
                                    MetricUnit.Gigabytes -> StandardUnit.GIGABYTES
                                    MetricUnit.Terabytes -> StandardUnit.TERABYTES
                                    MetricUnit.Bits -> StandardUnit.BITS
                                    MetricUnit.Kilobits -> StandardUnit.KILOBITS
                                    MetricUnit.Megabits -> StandardUnit.MEGABITS
                                    MetricUnit.Gigabits -> StandardUnit.GIGABITS
                                    MetricUnit.Terabits -> StandardUnit.TERABITS
                                    MetricUnit.Percent -> StandardUnit.PERCENT
                                    MetricUnit.Count -> StandardUnit.COUNT
                                    MetricUnit.BytesPerSecond -> StandardUnit.BYTES_SECOND
                                    MetricUnit.KilobytesPerSecond -> StandardUnit.KILOBYTES_SECOND
                                    MetricUnit.MegabytesPerSecond -> StandardUnit.MEGABYTES_SECOND
                                    MetricUnit.GigabytesPerSecond -> StandardUnit.GIGABYTES_SECOND
                                    MetricUnit.TerabytesPerSecond -> StandardUnit.TERABYTES_SECOND
                                    MetricUnit.BitsPerSecond -> StandardUnit.BITS_SECOND
                                    MetricUnit.KilobitsPerSecond -> StandardUnit.KILOBITS_SECOND
                                    MetricUnit.MegabitsPerSecond -> StandardUnit.MEGABITS_SECOND
                                    MetricUnit.GigabitsPerSecond -> StandardUnit.GIGABITS_SECOND
                                    MetricUnit.TerabitsPerSecond -> StandardUnit.TERABITS_SECOND
                                    MetricUnit.CountPerSecond -> StandardUnit.COUNT_SECOND
                                    MetricUnit.Other -> StandardUnit.NONE
                                }
                            )
                            .timestamp(it.time)
                            .value(it.value)
                            .dimensions(
                                Dimension.builder().name("Entry Point").value("All").build()
                            )
                            .build()
                    )
                })
                it.namespace(namespace)
            }.await()
        }
    }
}