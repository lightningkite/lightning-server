package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.gibibytes
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.model.Architecture
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public abstract class TerraformAwsServerlessBuilder<S : ServerBuilder>(
    override val builder: S,
) : BaseTerraformEmitter<S>(), TerraformEmitterAws {
    public abstract val storageBucket: String
    public abstract val region: Region
    public abstract val displayName: String
    public override val deploymentTag: String get() = displayName
    public override val projectPrefix: String
        get() = displayName.lowercase().replace(" ", "-").filter { it.isLetterOrDigit() || it == '-' }
    public open val storageBucketPath: String get() = projectPrefix
    public open val storageEncryptionEnabled: Boolean get() = true
    override val terraformRoot: File get() = File("terraform/$projectPrefix")
    override val secretsSource: SecretSource by lazy {
        val fetcher = PasswordFetcher()
        ManySecretSources(
            EnvironmentSecretSource,
            EncryptedFileSecretSource(
                storageBucket.substringAfterLast('/') + "_" + projectPrefix,
                passwordFetcher = fetcher
            ),
            EncryptedFileSecretSource(storageBucket.substringAfterLast('/'), passwordFetcher = fetcher),
        )
    }

    public abstract val handler: KClass<out AwsAdapter>
    public abstract val debug: Boolean
    public abstract val emergencyContact: EmailAddress

    public val architecture: Architecture get() = Architecture.X86_64

    public open val snapStart: Boolean get() = true
    public open val timeout: Duration get() = 30.seconds
    public open val memory: DataSize get() = 1.gibibytes
    public open val monthlyBudgetUsd: Double get() = 10.00

    public open val alarms: Map<String, LambdaAlarm>
        get() = LambdaAlarm.defaultSpendAlarms(
            computeSecondsPerMonth = (monthlyBudgetUsd / memory.gibibytes / 0.0000166667).seconds,
            description = "$displayName Lambda Spend"
        ).associate { it.description.lowercase().filter { it.isLetterOrDigit() || it == '-' } to it }

    override val additionalSettings: Set<ServerSetting<*, *>> = setOf(
        secretBasis,
        telemetrySettings,
        loggingSettings,
    )
    override val applicationRegion: String get() = region.id()
    override val policyStatements: MutableCollection<AwsPolicyStatement> = ArrayList()

    /**
     * A list of ARNs of lambda layers you need.
     */
    public val lambdaLayers: MutableList<String> = ArrayList<String>()

    public val lambdaEnvironment: MutableMap<String, String> = HashMap()

    /**
     * Files to add to the Lambda zip package.
     * Key = filename (relative to Lambda root, e.g., "collector.yaml")
     * Value = file content
     */
    public val lambdaFiles: MutableMap<String, String> = HashMap()

    /**
     * Lambda tracing mode for X-Ray integration.
     * - "Active": Lambda creates trace segment, propagates context
     * - "PassThrough": Lambda passes incoming trace context but doesn't create segment
     * - null: No tracing_config block (default)
     */
    public var lambdaTracingMode: LambdaTracingMode? = null

    public enum class LambdaTracingMode { Active, PassThrough }

    /**
     * Whether to attach X-Ray write access policy to Lambda role.
     * Set automatically by OTEL functions when using X-Ray exporter.
     */
    public var attachXRayPolicy: Boolean = false

    /**
     * Whether to use CloudFront in front of the WebSocket API Gateway for path-based routing.
     *
     * When enabled, the `ws.{domain}` endpoint will be served by CloudFront, which transforms
     * URI paths into the `path` query parameter before forwarding to API Gateway.
     *
     * This is required for services like Twilio that connect via path-based WebSocket URLs
     * (e.g., `wss://ws.domain.com/voice/call`) because AWS API Gateway WebSocket API
     * doesn't support path-based routing natively.
     *
     * When disabled (default), `ws.{domain}` points directly to API Gateway and clients
     * must pass the path as a query parameter (e.g., `wss://ws.domain.com?path=/voice/call`).
     */
    public open val useCloudFrontForWebSocket: Boolean get() = false

    /** IP Stack Configuration. */
    public open val enableIPv6: Boolean = true


    internal class VpcInfoTerraformManaged(
        val ipPrefix: String,
        val availabilityZones: List<String>,
        val natGateway: AwsVpc.NatGateway,
        override val id: String, //= TerraformJsonObject.expression("module.vpc.vpc_id"),
        override val securityGroup: String, //= TerraformJsonObject.expression("aws_security_group.internal.id"),
        override val privateSubnets: String, //= TerraformJsonObject.expression("module.vpc.private_subnets"),
        override val publicSubnets: String, //= TerraformJsonObject.expression("module.vpc.public_subnets"),
        override val applicationSubnet: String, //= TerraformJsonObject.expression("module.vpc.public_subnets[0]"),
        override val natGatewayIps: String, //= TerraformJsonObject.expression("module.vpc.nat_public_ips"),
        override val cidr: String = "$ipPrefix.0.0/16",
    ) : AwsVpc.VpcInfo

    public fun terraformManagedVPC(
        ipPrefix: String,
        availabilityZones: List<String>,
        natGateway: AwsVpc.NatGateway,
    ): AwsVpc.VpcInfo = VpcInfoTerraformManaged(
        ipPrefix = ipPrefix,
        availabilityZones = availabilityZones,
        natGateway = natGateway,
        id = TerraformJsonObject.expression("module.vpc.vpc_id"),
        securityGroup = TerraformJsonObject.expression("aws_security_group.internal.id"),
        privateSubnets = TerraformJsonObject.expression("module.vpc.private_subnets"),
        publicSubnets = TerraformJsonObject.expression("module.vpc.public_subnets"),
        applicationSubnet = TerraformJsonObject.expression("module.vpc.public_subnets[0]"),
        natGatewayIps = TerraformJsonObject.expression("module.vpc.nat_public_ips"),
    )

    override fun prepareForWrite() {

        if (projectPrefix.any { !it.isLetterOrDigit() && !(it == '-' || it == '_') })
            throw IllegalArgumentException("The projectPrefix has illegal characters in it. It can only contain: Letters, Digits, '-', and '_'.")

        super.prepareForWrite()
        require(TerraformProviderImport.aws)
        require(
            TerraformProvider(
                TerraformProviderImport.aws,
                null,
                buildJsonObject { put("region", region.id()) })
        )
        val emitter = this@TerraformAwsServerlessBuilder

        fulfillSetting(generalSettings.name, buildJsonObject {
            put("projectName", displayName)
            put(
                "publicUrl",
                (emitter as? TerraformEmitterAwsDomain)?.domain?.let { "https://$it" }
                    ?: $$"${aws_apigatewayv2_stage.http.invoke_url}")
            put(
                "wsUrl",
                (emitter as? TerraformEmitterAwsDomain)?.domain?.let {
                    if (useCloudFrontForWebSocket) "wss://ws.$it"
                    else "wss://ws.$it?path="
                }
                    ?: $$"${aws_apigatewayv2_stage.ws.invoke_url}")
            put("debug", debug)
            put("emergencyContact", emergencyContact.raw)
        })

        fulfillSetting("awsApiGatewayWsEndpointSetting", JsonPrimitive($$"${aws_apigatewayv2_stage.ws.invoke_url}"))

        val accessLogFormat = Json.encodeToString(terraformJsonObject {
            "requestId" - $$"$context.requestId"
            "sourceIp" - $$"$context.identity.sourceIp"
            "requestTime" - $$"$context.requestTime"
            "protocol" - $$"$context.protocol"
            "httpMethod" - $$"$context.httpMethod"
            "resourcePath" - $$"$context.resourcePath"
            "routeKey" - $$"$context.routeKey"
            "status" - $$"$context.status"
            "responseLength" - $$"$context.responseLength"
            "integrationErrorMessage" - $$"$context.integrationErrorMessage"
        })

        emit("http") {
            // HTTP
            "resource.aws_apigatewayv2_api.http" {
                "name" - "${emitter.projectPrefix}-http"
                "protocol_type" - "HTTP"
            }
            "resource.aws_apigatewayv2_stage.http" {
                "api_id" - expression("aws_apigatewayv2_api.http.id")

                "name" - "${emitter.projectPrefix}-gateway-stage"
                "auto_deploy" - true

                "access_log_settings" {
                    "destination_arn" - expression("aws_cloudwatch_log_group.http_api.arn")

                    "format" - accessLogFormat
                }
            }
            "resource.aws_apigatewayv2_integration.http" {
                "api_id" - expression("aws_apigatewayv2_api.http.id")

                "integration_uri" - expression("aws_lambda_alias.main.invoke_arn")
                "integration_type" - "AWS_PROXY"
                "integration_method" - "POST"
            }
            "resource.aws_cloudwatch_log_group.http_api" {
                "name" - "${emitter.projectPrefix}-http-gateway-log"

                "retention_in_days" - 30
            }
            "resource.aws_apigatewayv2_route.http" {
                "api_id" - expression("aws_apigatewayv2_api.http.id")
                "route_key" - $$"$default"
                "target" - $$"integrations/${aws_apigatewayv2_integration.http.id}"
            }
            "resource.aws_lambda_permission.api_gateway_http" {
                "action" - "lambda:InvokeFunction"
                "function_name" - expression("aws_lambda_alias.main.function_name")
                "qualifier" - expression("aws_lambda_alias.main.name")
                "principal" - "apigateway.amazonaws.com"

                "source_arn" - $$"${aws_apigatewayv2_api.http.execution_arn}/*/*"
                "lifecycle" {
                    "create_before_destroy" - true
                }
            }
            (emitter as? TerraformEmitterAwsDomain)?.domain?.let { domainName ->
                require(
                    TerraformProvider(
                        TerraformProviderImport.aws,
                        "acm",
                        buildJsonObject { put("region", "us-east-1") })
                )
                val zone = emitter.domainZoneId
                "resource.aws_acm_certificate.http" {
                    "domain_name" - domainName
                    "validation_method" - "DNS"
                }
                "resource.aws_route53_record.http" {
                    "zone_id" - zone
                    "name" - expression("tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_name")
                    "type" - expression("tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_type")
                    "records" - listOf(expression("tolist(aws_acm_certificate.http.domain_validation_options)[0].resource_record_value"))
                    "ttl" - "300"
                }
                "resource.aws_acm_certificate_validation.http" {
                    "certificate_arn" - expression("aws_acm_certificate.http.arn")
                    "validation_record_fqdns" - listOf(expression("aws_route53_record.http.fqdn"))
                }
                "resource.aws_apigatewayv2_domain_name.http" {
                    "domain_name" - domainName
                    "domain_name_configuration" {
                        "certificate_arn" - expression("aws_acm_certificate.http.arn")
                        "endpoint_type" - "REGIONAL"
                        "security_policy" - "TLS_1_2"
                        if (enableIPv6)
                            "ip_address_type" - "dualstack"
                    }
                    "depends_on" - listOf("aws_acm_certificate_validation.http")
                }
                "resource.aws_apigatewayv2_api_mapping.http" {
                    "stage" - expression("aws_apigatewayv2_stage.http.id")
                    "api_id" - expression("aws_apigatewayv2_stage.http.api_id")
                    "domain_name" - expression("aws_apigatewayv2_domain_name.http.domain_name")
                }
                "resource.aws_route53_record.httpAccess" {
                    "type" - "A"
                    "name" - expression("aws_apigatewayv2_domain_name.http.domain_name")
                    "zone_id" - zone
                    "alias" {
                        "evaluate_target_health" - false
                        "name" - expression("aws_apigatewayv2_domain_name.http.domain_name_configuration[0].target_domain_name")
                        "zone_id" - expression("aws_apigatewayv2_domain_name.http.domain_name_configuration[0].hosted_zone_id")
                    }
                }
                if (enableIPv6)
                    "resource.aws_route53_record.httpAccessIpv6" {
                        "type" - "AAAA"
                        "name" - expression("aws_apigatewayv2_domain_name.http.domain_name")
                        "zone_id" - zone
                        "alias" {
                            "evaluate_target_health" - false
                            "name" - expression("aws_apigatewayv2_domain_name.http.domain_name_configuration[0].target_domain_name")
                            "zone_id" - expression("aws_apigatewayv2_domain_name.http.domain_name_configuration[0].hosted_zone_id")
                        }
                    }
            }
        }
        emit("ws") {
            "resource.aws_apigatewayv2_api.ws" {
                "name" - "${emitter.projectPrefix}-gateway"
                "protocol_type" - "WEBSOCKET"
                "route_selection_expression" - "constant"
            }
            "resource.aws_apigatewayv2_stage.ws" {
                "api_id" - expression("aws_apigatewayv2_api.ws.id")

                "name" - "${emitter.projectPrefix}-gateway-stage"
                "auto_deploy" - true

                "access_log_settings" {
                    "destination_arn" - expression("aws_cloudwatch_log_group.ws_api.arn")

                    "format" - accessLogFormat
                }
            }
            "resource.aws_apigatewayv2_integration.ws" {
                "api_id" - expression("aws_apigatewayv2_api.ws.id")

                "integration_uri" - expression("aws_lambda_alias.main.invoke_arn")
                "integration_type" - "AWS_PROXY"
                "integration_method" - "POST"
            }
            "resource.aws_cloudwatch_log_group.ws_api" {
                "name" - "${emitter.projectPrefix}-ws-gateway-log"

                "retention_in_days" - 30
            }
            "resource.aws_apigatewayv2_route.ws_connect" {
                "api_id" - expression("aws_apigatewayv2_api.ws.id")

                "route_key" - "\$connect"
                "target" - $$"integrations/${aws_apigatewayv2_integration.ws.id}"
            }
            "resource.aws_apigatewayv2_route.ws_default" {
                "api_id" - expression("aws_apigatewayv2_api.ws.id")

                "route_key" - "\$default"
                "target" - $$"integrations/${aws_apigatewayv2_integration.ws.id}"
            }
            "resource.aws_apigatewayv2_route.ws_disconnect" {
                "api_id" - expression("aws_apigatewayv2_api.ws.id")

                "route_key" - "\$disconnect"
                "target" - $$"integrations/${aws_apigatewayv2_integration.ws.id}"
            }
            "resource.aws_lambda_permission.api_gateway_ws" {
                "action" - "lambda:InvokeFunction"
                "function_name" - expression("aws_lambda_alias.main.function_name")
                "qualifier" - expression("aws_lambda_alias.main.name")
                "principal" - "apigateway.amazonaws.com"

                "source_arn" - $$"${aws_apigatewayv2_api.ws.execution_arn}/*/*"
                "lifecycle" {
                    "create_before_destroy" - true
                }
            }
            emitter.policyStatements += AwsPolicyStatement(
                action = listOf("execute-api:ManageConnections"),
                resource = listOf("*")
            )
            (emitter as? TerraformEmitterAwsDomain)?.let { cloudInfo ->
                require(
                    TerraformProvider(
                        TerraformProviderImport.aws,
                        "acm",
                        buildJsonObject { put("region", "us-east-1") })
                )
                val domainName = emitter.domain
                val zone = emitter.domainZoneId
                "resource.aws_acm_certificate.ws" {
                    "domain_name" - "ws.${domainName}"
                    "validation_method" - "DNS"
                }
                "resource.aws_route53_record.ws" {
                    "zone_id" - zone
                    "name" - expression("tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_name")
                    "type" - expression("tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_type")
                    "records" - listOf(expression("tolist(aws_acm_certificate.ws.domain_validation_options)[0].resource_record_value"))
                    "ttl" - "300"
                }
                "resource.aws_acm_certificate_validation.ws" {
                    "certificate_arn" - expression("aws_acm_certificate.ws.arn")
                    "validation_record_fqdns" - listOf(expression("aws_route53_record.ws.fqdn"))
                }

                if (useCloudFrontForWebSocket) {
                    // CloudFront distribution for path-based WebSocket routing
                    emitCloudFrontWebSocket(domainName, zone, "${emitter.projectPrefix}-gateway-stage")
                } else {
                    // Direct API Gateway custom domain (default)
                    "resource.aws_apigatewayv2_domain_name.ws" {
                        "domain_name" - "ws.${domainName}"
                        "domain_name_configuration" {
                            "certificate_arn" - expression("aws_acm_certificate.ws.arn")
                            "endpoint_type" - "REGIONAL"
                            "security_policy" - "TLS_1_2"
                            if (enableIPv6)
                                "ip_address_type" - "dualstack"
                        }
                        "depends_on" - listOf("aws_acm_certificate_validation.ws")
                    }
                    "resource.aws_apigatewayv2_api_mapping.ws" {
                        "stage" - expression("aws_apigatewayv2_stage.ws.id")
                        "api_id" - expression("aws_apigatewayv2_stage.ws.api_id")
                        "domain_name" - expression("aws_apigatewayv2_domain_name.ws.domain_name")
                    }
                    "resource.aws_route53_record.wsAccess" {
                        "type" - "A"
                        "name" - expression("aws_apigatewayv2_domain_name.ws.domain_name")
                        "zone_id" - zone
                        "alias" {
                            "evaluate_target_health" - false
                            "name" - expression("aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].target_domain_name")
                            "zone_id" - expression("aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].hosted_zone_id")
                        }
                    }
                    if (enableIPv6)
                        "resource.aws_route53_record.wsAccessIpv6" {
                            "type" - "AAAA"
                            "name" - expression("aws_apigatewayv2_domain_name.ws.domain_name")
                            "zone_id" - zone
                            "alias" {
                                "evaluate_target_health" - false
                                "name" - expression("aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].target_domain_name")
                                "zone_id" - expression("aws_apigatewayv2_domain_name.ws.domain_name_configuration[0].hosted_zone_id")
                            }
                        }
                }
            }
        }
        emit("lambdaAlarms") {
            val functionName = expression("aws_lambda_function.main.function_name")
            "resource.aws_sns_topic.emergency" {
                "name" - "${emitter.projectPrefix}_emergencies"
            }
            "resource.aws_sns_topic_subscription.emergency_primary" {
                "topic_arn" - expression("aws_sns_topic.emergency.arn")
                "protocol" - "email"
                "endpoint" - emergencyContact.raw
            }
            alarms.entries.forEach { (key, value) ->
                "resource.aws_cloudwatch_metric_alarm.${key}" {
                    "alarm_name" - "${emitter.projectPrefix}_${key}"
                    "comparison_operator" - "GreaterThanOrEqualToThreshold"
                    "evaluation_periods" - value.evaluationPeriods
                    "datapoints_to_alarm" - value.dataPointsToAlarm
                    "period" - value.period.inWholeSeconds
                    "statistic" - value.statistic.name
                    "threshold" - value.threshold
                    "metric_name" - value.metric.name
                    "namespace" - "AWS/Lambda"
                    "alarm_description" - value.description
                    "insufficient_data_actions" - listOf<JsonObject>()
                    "dimensions" {
                        "FunctionName" - functionName
                    }
                    "alarm_actions" - listOf(expression("aws_sns_topic.emergency.arn"))
                }
            }
            "resource.aws_api_gateway_account.main" {
                "cloudwatch_role_arn" - expression("aws_iam_role.cloudwatch.arn")
            }
            "resource.aws_iam_role.cloudwatch" {
                "name" - emitter.projectPrefix

                "assume_role_policy" - Json.encodeToString(terraformJsonObject {
                    "Version" - "2012-10-17"
                    "Statement" - listOf(
                        terraformJsonObject {
                            "Sid" - ""
                            "Effect" - "Allow"
                            "Principal" {
                                "Service" - listOf("apigateway.amazonaws.com", "lambda.amazonaws.com")
                            }
                            "Action" - "sts:AssumeRole"
                        }
                    )
                })
            }
            "resource.aws_iam_role_policy.cloudwatch" {
                "name" - "${emitter.projectPrefix}_policy"
                "role" - expression("aws_iam_role.cloudwatch.id")

                "policy" - Json.encodeToString(terraformJsonObject {
                    "Version" - "2012-10-17"
                    "Statement" - listOf(
                        terraformJsonObject {
                            "Effect" - "Allow"
                            "Action" - listOf(
                                "logs:CreateLogGroup",
                                "logs:CreateLogStream",
                                "logs:DescribeLogGroups",
                                "logs:DescribeLogStreams",
                                "logs:PutLogEvents",
                                "logs:GetLogEvents",
                                "logs:FilterLogEvents"
                            )
                            "Resource" - "*"
                        }
                    )
                })
            }
        }
        emit("lambda") {
            // BASELINE LAMBDA
            "resource.aws_s3_bucket.lambda_bucket" {
                // .lowercase().replace("_", "") on projectPrefix is backwards compatibility with V4 and earlier.
                // We can't really remove this at this time.
                "bucket_prefix" - "${emitter.projectPrefix.lowercase().replace("_", "")}-lambda-bucket"
                "force_destroy" - true
            }
            "resource.aws_iam_role.main_exec" {
                "name" - "${emitter.projectPrefix}-main-exec"

                "assume_role_policy" - Json.encodeToString(buildJsonObject {
                    put("Version", "2012-10-17")
                    putJsonArray("Statement") {
                        addJsonObject {
                            put("Action", "sts:AssumeRole")
                            put("Effect", "Allow")
                            put("Sid", "")
                            putJsonObject("Principal") {
                                put("Service", "lambda.amazonaws.com")
                            }
                        }
                    }
                })
            }
            emitter.policyStatements += AwsPolicyStatement(
                action = listOf("s3:GetObject"),
                resource = listOf(
                    $$"${aws_s3_bucket.lambda_bucket.arn}",
                    $$"${aws_s3_bucket.lambda_bucket.arn}/*",
                )
            )
            emitter.policyStatements += AwsPolicyStatement(
                action = listOf("dynamodb:*"),
                resource = listOf(
                    "*" // TODO: constrain this
                )
            )
            emitter.policyStatements += AwsPolicyStatement(
                action = listOf("lambda:InvokeFunction"),
                resource = listOf(
                    "*" // TODO: constrain this - how?  circular dependency?!
                )
            )
            "locals" {
                val j = Json { encodeDefaults = true; explicitNulls = false }
                "servicesAccessPolicy" - buildJsonObject {
                    put("Version", "2012-10-17")
                    put(
                        "Statement",
                        j.encodeToJsonElement(emitter.policyStatements.toList())
                    )
                }
            }
            "resource.aws_iam_policy.servicesAccess" {
                "name" - "${emitter.projectPrefix}-servicesAccess"
                "path" - "/${emitter.projectPrefixPath}/servicesAccess/"
                "description" - "Access to the ${emitter.projectPrefix} services"
                "policy" - expression("jsonencode(local.servicesAccessPolicy)")
            }
            "resource.aws_iam_role_policy_attachment.servicesAccess" {
                "role" - expression("aws_iam_role.main_exec.name")
                "policy_arn" - expression("aws_iam_policy.servicesAccess.arn")
            }
            "resource.aws_iam_role_policy_attachment.main_policy_exec" {
                "role" - expression("aws_iam_role.main_exec.name")
                "policy_arn" - "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
            }
            "resource.aws_iam_role_policy_attachment.main_policy_vpc" {
                "role" - expression("aws_iam_role.main_exec.name")
                "policy_arn" - "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
            }
            "resource.aws_iam_role_policy_attachment.insights_policy" {
                "role" - expression("aws_iam_role.main_exec.id")
                "policy_arn" - "arn:aws:iam::aws:policy/CloudWatchLambdaInsightsExecutionRolePolicy"
            }
            if (emitter.attachXRayPolicy) {
                "resource.aws_iam_role_policy_attachment.xray_write_access" {
                    "role" - expression("aws_iam_role.main_exec.name")
                    "policy_arn" - "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
                }
            }
            "resource.aws_s3_object.app_storage" {
                "bucket" - expression("aws_s3_bucket.lambda_bucket.id")

                "key" - "lambda-functions.zip"
                "source" - expression("data.archive_file.lambda.output_path")

                "source_hash" - expression("data.archive_file.lambda.output_md5")
                "depends_on" - listOf("data.archive_file.lambda")
            }
            "resource.aws_lambda_function.main" {
                "function_name" - "${emitter.projectPrefix}-main"
                "publish" - snapStart
                "s3_bucket" - expression("aws_s3_bucket.lambda_bucket.id")
                "s3_key" - expression("aws_s3_object.app_storage.key")

                "runtime" - "java17"
                "handler" - (handler.qualifiedName ?: throw IllegalArgumentException("AWS Handler must have a FQN"))

                "memory_size" - memory.inWholeMebibytes
                "timeout" - timeout.inWholeSeconds

                "layers" - lambdaLayers

                "source_code_hash" - expression("data.archive_file.lambda.output_base64sha256")

                "role" - expression("aws_iam_role.main_exec.arn")

                "snap_start" {
                    "apply_on" - "PublishedVersions"
                }

                val vpcInfo = emitter.applicationVpc as? AwsVpc.VpcInfo
                if (vpcInfo != null) {
                    "vpc_config" {
                        "ipv6_allowed_for_dual_stack" - enableIPv6
                        "subnet_ids" - vpcInfo.privateSubnets
                        "security_group_ids" - listOf(
                            expression("aws_security_group.internal.id"),
                            expression("aws_security_group.access_outside.id")
                        )
                    }
                }

                emitter.lambdaTracingMode?.let { mode ->
                    "tracing_config" {
                        "mode" - mode.name
                    }
                }

                "environment" {
                    "variables" {
                        "LIGHTNING_SERVER_SETTINGS_DECRYPTION" - expression("random_password.settings.result")
                        lambdaEnvironment.forEach { (key, value) ->
                            key - value
                        }
                    }
                }

                "depends_on" - listOf("aws_s3_object.app_storage")
            }
            "resource.aws_lambda_alias.main" {
                "name" - "prod"
                "description" - "The current production version of the lambda."
                "function_name" - expression("aws_lambda_function.main.arn")
                "function_version" - (if (snapStart) expression("aws_lambda_function.main.version") else "\$LATEST")
            }
            "resource.aws_cloudwatch_log_group.main" {
                "name" - "${emitter.projectPrefix}-main-log"
                "retention_in_days" - 30
            }
            "locals" {
                "settings_raw" - JsonObject(settings)
            }
            "resource.local_sensitive_file.settings_raw" {
                "content" - expression("jsonencode(local.settings_raw)")
                "filename" - $$"${path.module}/build/raw-settings.json"
            }
            "locals" {
                // Directories start with "C:..." on Windows; All other OSs use "/" for root.
                "is_windows" - expression("substr(pathexpand(\"~\"), 0, 1) == \"/\" ? false : true")
            }
            // Lambda files (e.g., custom collector config)
            lambdaFiles.forEach { (filename, content) ->
                val resourceName = "lambda_" + filename.replace(".", "_").replace("/", "_")
                "resource.local_file.$resourceName" {
                    "content" - content
                    "filename" - "\${path.module}/build/$filename"
                }
            }
            "resource.null_resource.lambda_jar_source" {
                "triggers" {
                    "buildHash" - expression($$"""sha256(join("", [for f in fileset("${path.module}/../../build/dist/lambda", "**") : filesha256("${path.module}/../../build/dist/lambda/${f}")]))""")
                    "settingsHash" - expression("local_sensitive_file.settings_raw.content_sha256")
                }
                "provisioner.local-exec" - (listOf(
                    terraformJsonObject {
                        "command" - this@emit.expression(
                            $$"""
                                local.is_windows ? "if(test-path \"${path.module}/build/lambda/\") { rd -Recurse \"${path.module}/build/lambda/\" }" : "rm -rf \"${path.module}/build/lambda/\""
                            """.trimIndent()
                        )
                        "interpreter" - this@emit.expression("local.is_windows ? [\"PowerShell\", \"-Command\"] : []")
                    }, terraformJsonObject {
                        "command" - this@emit.expression(
                            $$"""
                                local.is_windows ? "cp -r -force \"${path.module}/../../build/dist/lambda/.\" \"${path.module}/build/lambda/\"" : "cp -rf \"${path.module}/../../build/dist/lambda/.\" \"${path.module}/build/lambda/\""
                            """.trimIndent()
                        )
                        "interpreter" - this@emit.expression("local.is_windows ? [\"PowerShell\", \"-Command\"] : []")
                    }, terraformJsonObject {
                        "command" - $$"openssl enc -aes-256-cbc -md sha256 -in \"${local_sensitive_file.settings_raw.filename}\" -out \"${path.module}/build/lambda/settings.enc\" -pass pass:${random_password.settings.result}"
                        "interpreter" - this@emit.expression("local.is_windows ? [\"PowerShell\", \"-Command\"] : []")
                    }
                ) + lambdaFiles.map { (filename, _) ->
                    val resourceName = "lambda_" + filename.replace(".", "_").replace("/", "_")
                    terraformJsonObject {
                        "command" - $$"""cp "${local_file.$$resourceName.filename}" "${path.module}/build/lambda/$$filename""""
                        "interpreter" - this@emit.expression("local.is_windows ? [\"PowerShell\", \"-Command\"] : []")
                    }
                })
            }
            "resource.null_resource.settings_reread" {
                "triggers" {
                    "settingsRawHash" - expression("local_sensitive_file.settings_raw.content_sha256")
                }
                "depends_on" - listOf("null_resource.lambda_jar_source")
                "provisioner.local-exec" {
                    "command" - $$"openssl enc -d -aes-256-cbc -md sha256 -out \"${local_sensitive_file.settings_raw.filename}.decrypted.json\" -in \"${path.module}/build/lambda/settings.enc\" -pass pass:${random_password.settings.result}"
                    "interpreter" - expression("local.is_windows ? [\"PowerShell\", \"-Command\"] : []")
                }
            }
            "resource.random_password.settings" {
                "length" - 32
                "special" - true
                "override_special" - "-_"
            }
            "data.archive_file.lambda" {
                "depends_on" - (listOf(
                    "null_resource.lambda_jar_source",
                    "null_resource.settings_reread"
                ) + lambdaFiles.keys.map { filename ->
                    "local_file.lambda_" + filename.replace(".", "_").replace("/", "_")
                })
                "type" - "zip"
                "source_dir" - $$"${path.module}/build/lambda"
                "output_path" - $$"${path.module}/build/lambda.jar"
            }
            "data.aws_caller_identity.lambda_current" {}
            "resource.aws_lambda_permission.scheduled_tasks" {
                "action" - "lambda:InvokeFunction"
                "function_name" - expression("aws_lambda_alias.main.function_name")
                "qualifier" - expression("aws_lambda_alias.main.name")
                "principal" - "events.amazonaws.com"
                "source_arn" - $$"arn:aws:events:$${emitter.applicationRegion}:${data.aws_caller_identity.lambda_current.account_id}:rule/$${emitter.projectPrefix}*"
                "lifecycle" {
                    "create_before_destroy" - true
                }
            }
        }
        emit("schedules") {
            for ((path, schedule) in builder.build().schedules) {
                val name = path.toString().filter { it.isLetterOrDigit() || it == '_' }
                "resource.aws_cloudwatch_event_rule.scheduled_task_$name" {
                    "name" - "${emitter.projectPrefix}_$name"
                    "schedule_expression" - when (val s = schedule.schedule) {
                        is Schedule.Daily -> "cron(${s.time.minute} ${s.time.hour} * * ? *)"
                        is Schedule.Frequency -> "rate(${s.gap.inWholeMinutes} minute${if (s.gap.inWholeMinutes > 1) "s" else ""})"
                        is Schedule.Cron -> "cron(${s.cron} *)"
                    }
                }
                "resource.aws_cloudwatch_event_target.scheduled_task_$name" {
                    "rule" - expression("aws_cloudwatch_event_rule.scheduled_task_$name.name")
                    "target_id" - "lambda"
                    "arn" - expression("aws_lambda_alias.main.arn")
                    "input" - "{\"scheduled\": \"$path\"}"
                }
            }
        }
        emit("main") {
            "terraform" {
                "required_providers" {
                    terraformProviderImports
                        .distinct()
                        .map { it.toTerraformJson() }
                        .forEach { include(it) }
                }
                "required_version" - "~> 1.0"
                "backend.s3" {
                    "bucket" - storageBucket
                    "key" - storageBucketPath
                    "region" - applicationRegion
                    "encrypt" - storageEncryptionEnabled
                }
            }
            if (terraformProviders.isNotEmpty()) {
                include(terraformProviders)
            }
        }
    }
}
