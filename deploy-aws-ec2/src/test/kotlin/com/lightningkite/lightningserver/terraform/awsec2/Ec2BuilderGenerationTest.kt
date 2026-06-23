package com.lightningkite.lightningserver.terraform.awsec2

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.terraform.aws.VpcInfoTerraformManaged
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsEc2BuilderBase
import com.lightningkite.lightningserver.terraform.aws.ec2.StigLevel
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsScalingEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.stigBuildLinux
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsSingleEc2Builder
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.terraform.AwsVpc
import kotlinx.serialization.json.*
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.test.*

/**
 * Generation-level tests: build both EC2 builders to a temporary directory and assert the
 * expected Terraform resources are emitted, plus the scaling builder's shared-cache guard.
 *
 * These do not call AWS; they only exercise the Kotlin -> Terraform JSON generation.
 */
class Ec2BuilderGenerationTest {

    object TestServer : ServerBuilder()

    /** A server that exposes a liveness endpoint at a non-default path, to prove autodetection. */
    object OnlineServer : ServerBuilder() {
        val online = path.path("api").path("online").get bind
            HttpHandler<PathSpec0> { HttpResponse(status = HttpStatus.OK) }
    }

    private val tmpRoot = File("build/test-terraform")

    /** Fulfills the non-optional global settings so [prepareForWrite] passes without real services. */
    private fun TerraformAwsEc2BuilderBase<*>.fulfillGlobals(cacheUrl: String? = "redis://cache:6379") {
        fulfillSetting(secretBasis.name, Json.encodeToJsonElement(secretBasis.serializer, secretBasis.default))
        fulfillSetting(loggingSettings.name, Json.encodeToJsonElement(loggingSettings.serializer, loggingSettings.default))
        fulfillSetting(telemetrySettings.name, JsonNull)
        if (cacheUrl != null) fulfillSetting("cache", JsonPrimitive(cacheUrl))
    }

    private val managedVpc = VpcInfoTerraformManaged(
        ipPrefix = "10.0",
        availabilityZones = listOf("us-west-2a", "us-west-2b"),
        natGateway = AwsVpc.NatGateway.Single,
    )

    inner class SingleDeployment : TerraformAwsSingleEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Single Test"
        override val domainZone = "example.com"
        override val domain = "single.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = AwsVpc.Default
        override val terraformRoot = File(tmpRoot, "single")
        override fun TestServer.settings() = fulfillGlobals(cacheUrl = null)
    }

    inner class ScalingDeployment(
        private val cacheUrl: String? = "redis://cache:6379",
    ) : TerraformAwsScalingEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Scaling Test"
        override val domainZone = "example.com"
        override val domain = "scaling.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = managedVpc
        override val terraformRoot = File(tmpRoot, "scaling")
        override fun TestServer.settings() = fulfillGlobals(cacheUrl)
    }

    inner class OnlineScalingDeployment : TerraformAwsScalingEc2Builder<OnlineServer>(OnlineServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Online Test"
        override val domainZone = "example.com"
        override val domain = "online.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = managedVpc
        override val scalingRequestsPerTarget = 300
        override val maxInstanceLifetimeSeconds = 604800
        override val terraformRoot = File(tmpRoot, "online")
        override fun OnlineServer.settings() = fulfillGlobals()
    }

    inner class CmkScalingDeployment : TerraformAwsScalingEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Cmk Test"
        override val domainZone = "example.com"
        override val domain = "cmk.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = managedVpc
        override val customerManagedKey = true
        override val wafEnabled = true
        override val hardeningComponents = listOf(stigBuildLinux(StigLevel.Low))
        override val terraformRoot = File(tmpRoot, "cmk")
        override fun TestServer.settings() = fulfillGlobals("redis://cache:6379")
    }

    /** Finds a single resource block (`resource.<type>.<name>`) across all generated files. */
    private fun File.findResource(type: String, name: String): JsonObject? {
        listFiles { f -> f.name.endsWith(".tf.json") }?.forEach { file ->
            (Json.parseToJsonElement(file.readText()).jsonObject["resource"] as? JsonObject)
                ?.get(type)?.jsonObject?.get(name)?.let { return it.jsonObject }
        }
        return null
    }

    /** Returns the set of resource type names present across all generated files. */
    private fun File.resourceTypes(): Set<String> = buildSet {
        listFiles { f -> f.name.endsWith(".tf.json") }?.forEach { file ->
            (Json.parseToJsonElement(file.readText()).jsonObject["resource"] as? JsonObject)
                ?.keys?.let { addAll(it) }
        }
    }

    @Test
    fun customerManagedKeyAndWafGenerate() {
        val d = CmkScalingDeployment()
        d.write()
        val types = d.terraformRoot.resourceTypes()
        // The shared CMK and the WAF are emitted.
        assertContains(types, "aws_kms_key")
        assertContains(types, "aws_wafv2_web_acl")
        assertContains(types, "aws_wafv2_web_acl_association")
        // EBS volumes, the log group, and the deployment-bucket SSE all reference the key.
        val ebs = d.terraformRoot.findResource("aws_launch_template", "app")!!
            .let { it["block_device_mappings"]!!.jsonObject["ebs"]!!.jsonObject }
        assertNotNull(ebs["kms_key_id"])
        val logGroup = d.terraformRoot.findResource("aws_cloudwatch_log_group", "application")!!
        assertNotNull(logGroup["kms_key_id"])
        val deploymentSse = d.terraformRoot.findResource("aws_s3_bucket_server_side_encryption_configuration", "deployment")!!
            .let { it["rule"]!!.jsonObject["apply_server_side_encryption_by_default"]!!.jsonObject }
        assertEquals("aws:kms", deploymentSse["sse_algorithm"]!!.jsonPrimitive.content)
        // The key policy must grant the Auto Scaling service-linked role, or the ASG can't launch instances.
        val keyPolicy = d.terraformRoot.findResource("aws_kms_key", "main")!!["policy"]!!.jsonPrimitive.content
        assertContains(keyPolicy, "AWSServiceRoleForAutoScaling")
        assertContains(keyPolicy, "logs.us-west-2.amazonaws.com")
        // The parameterized STIG component is present with its Level parameter.
        val components = d.terraformRoot.findResource("aws_imagebuilder_image_recipe", "this")!!["component"]!!.jsonArray
        val stig = components.map { it.jsonObject }.single { it["component_arn"]!!.jsonPrimitive.content.contains("stig-build-linux") }
        assertContains(stig["component_arn"]!!.jsonPrimitive.content, "component/stig-build-linux/x.x.x")
        val levelParam = stig["parameter"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "Level" }
        assertEquals("Low", levelParam["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun singleInstanceGenerates() {
        val d = SingleDeployment()
        d.write()
        val types = d.terraformRoot.resourceTypes()
        assertContains(types, "aws_instance")
        assertContains(types, "aws_eip")
        // IMDSv2 must be enforced on the instance.
        val httpTokens = d.terraformRoot.findResource("aws_instance", "ubuntu")!!
            .let { it["metadata_options"]!!.jsonObject["http_tokens"]!!.jsonPrimitive.content }
        assertEquals("required", httpTokens)
    }

    @Test
    fun scalingGenerates() {
        val d = ScalingDeployment()
        d.write()
        val types = d.terraformRoot.resourceTypes()
        assertContains(types, "aws_lb")
        assertContains(types, "aws_lb_target_group")
        assertContains(types, "aws_autoscaling_group")
        assertContains(types, "aws_launch_template")
        assertContains(types, "aws_imagebuilder_image")
        assertContains(types, "aws_acm_certificate")
        // IMDSv2 must be enforced on the launch template.
        val httpTokens = d.terraformRoot.findResource("aws_launch_template", "app")!!
            .let { it["metadata_options"]!!.jsonObject["http_tokens"]!!.jsonPrimitive.content }
        assertEquals("required", httpTokens)
    }

    @Test
    fun scalingRejectsRamCache() {
        val ex = assertFailsWith<IllegalStateException> {
            ScalingDeployment(cacheUrl = "ram").write()
        }
        assertTrue(ex.message!!.contains("distributed cache"), "Unexpected message: ${ex.message}")
    }

    @Test
    fun scalingIgnoresDesiredCapacity() {
        val d = ScalingDeployment()
        d.write()
        val ignore = d.terraformRoot.findResource("aws_autoscaling_group", "app")!!
            .let { it["lifecycle"]!!.jsonObject["ignore_changes"]!!.jsonArray.map { e -> e.jsonPrimitive.content } }
        assertContains(ignore, "desired_capacity")
        // CPU-only by default: no request-count policy.
        assertNull(d.terraformRoot.findResource("aws_autoscaling_policy", "requests"))
    }

    @Test
    fun scalingAutodetectsOnlinePathAndOptionalKnobs() {
        val d = OnlineScalingDeployment()
        d.write()
        // Health check uses the autodetected liveness path, not the /meta/online fallback.
        val path = d.terraformRoot.findResource("aws_lb_target_group", "app")!!
            .let { it["health_check"]!!.jsonObject["path"]!!.jsonPrimitive.content }
        assertEquals("/api/online", path)
        // Request-count policy present when scalingRequestsPerTarget is set.
        assertNotNull(d.terraformRoot.findResource("aws_autoscaling_policy", "requests"))
        // Max instance lifetime wired through.
        val maxLife = d.terraformRoot.findResource("aws_autoscaling_group", "app")!!
            .let { it["max_instance_lifetime"]!!.jsonPrimitive.int }
        assertEquals(604800, maxLife)
    }
}
