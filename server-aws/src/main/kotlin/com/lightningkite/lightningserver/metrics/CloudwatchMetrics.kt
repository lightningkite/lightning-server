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

class CloudwatchMetrics(
    override val settings: MetricSettings,
    val region: Region,
    val credentialProvider: AwsCredentialsProvider,
) : Metrics {
    companion object {
        init {
            MetricSettings.register("cloudwatch") {
                Regex("""cloudwatch:\/\/((?:(?<user>[a-zA-Z0-9+\/]+):(?<password>[a-zA-Z0-9+\/]+)@)?(?<region>[a-z0-9-]+))""").matchEntire(it.url)?.let { match ->
                    val user = match.groups["user"]?.value ?: ""
                    val password = match.groups["password"]?.value ?: ""
                    val region = Region.of(match.groups["region"]!!.value)
                    CloudwatchMetrics(
                        it,
                        region,
                        if (user.isNotBlank() && password.isNotBlank()) {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        } else DefaultCredentialsProvider.create(),
                    )
                } ?: throw IllegalStateException("Invalid CloudWatch metrics URL. The URL should match the pattern: cloudwatch://[user]:[password]@[region]")
            }
        }
    }

    val cw = CloudWatchAsyncClient.builder()
        .region(region)
        .credentialsProvider(credentialProvider)
        .build()

    override suspend fun report(events: List<MetricEvent>) {
        cw.putMetricData {
            it.metricData(events.map {
                MetricDatum.builder()
                    .metricName(it.type)
                    .timestamp(it.time)
                    .value(it.value)
                    .dimensions(
                        Dimension.builder().name("Entry Point").value(it.entryPoint).build()
                    )
                    .build()
            })
            it.namespace(generalSettings().projectName)
        }.await().let { println(it) }
    }
}