// Own package so the chapter can use plain names (Server, Production).
package com.lightningkite.lightningserver.guide.samples.terraform

// region imports
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.terraform.SecretsManagerSecretSource
import com.lightningkite.lightningserver.terraform.EnvironmentSecretSource
import com.lightningkite.lightningserver.terraform.ManySecretSources
import com.lightningkite.lightningserver.terraform.SecretSource
import com.lightningkite.lightningserver.terraform.awsserverless.TerraformAwsServerlessDomainBuilder
import com.lightningkite.lightningserver.terraform.generated
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.mongodb.MongoDatabase
import com.lightningkite.services.database.mongodb.mongodbAtlasFree
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.byVariable
import com.lightningkite.services.terraform.direct
import kotlinx.serialization.json.JsonPrimitive
import software.amazon.awssdk.regions.Region
import kotlin.reflect.KClass
// endregion

// region server
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val jobQueueUrl = setting("jobQueueUrl", "")
    val stripeSecretKey = setting("stripeSecretKey", "")
    val supportEmail = setting("supportEmail", "")

    init {
        MongoDatabase
    }
}

class AwsHandler : AwsAdapter(Server.build())
// endregion

// region custom-function
context(emitter: TerraformEmitterAws)
fun TerraformNeed<String>.awsSqsQueue() {
    emitter.emit(name) {
        "resource.aws_sqs_queue.$name" {
            "name" - "${emitter.projectPrefix}-$name"
            "message_retention_seconds" - 86400
        }
    }
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf("sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage"),
        resource = listOf(expression("aws_sqs_queue.$name.arn")),
    )
    emitter.fulfillSetting(name, JsonPrimitive(expression("aws_sqs_queue.$name.url")))
}
// endregion

// region deployment
object Production : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    override val handler: KClass<out AwsAdapter> = AwsHandler::class
    override val displayName = "Shop API"
    override val region: Region = Region.US_WEST_2
    override val storageBucket = "my-company-terraform-state"
    override val domainZone = "example.com"
    override val domain = "api.example.com"
    override val debug = false
    override val emergencyContact = "ops@example.com".toEmailAddress()
    override val applicationVpc: AwsVpc = AwsVpc.None

    // CI sets LS_SECRET_* variables; people at a terminal share secrets through Secrets Manager.
    override val secretsSource: SecretSource by lazy {
        ManySecretSources(
            EnvironmentSecretSource,
            SecretsManagerSecretSource(profile = "my-company", idPrefix = "shop-api", region = region),
        )
    }

    override fun Server.settings() {
        database.mongodbAtlasFree(orgId = "your-atlas-org-id")
        jobQueueUrl.awsSqsQueue()
        stripeSecretKey.byVariable()
        supportEmail.direct("support@example.com")
        secretBasis.generated()
        loggingSettings.direct(LoggingSettings())
        telemetrySettings.direct(TelemetryBackend.Settings())

        policyStatements += AwsPolicyStatement(
            action = listOf("ses:SendEmail", "ses:SendRawEmail"),
            resource = listOf("*"),
        )
        lambdaEnvironment["JAVA_TOOL_OPTIONS"] = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }

    override fun prepareForWrite() {
        super.prepareForWrite()
        emit("lambda") {
            "resource.aws_lambda_function.main" {
                "reserved_concurrent_executions" - 50
            }
        }
    }
}
// endregion
