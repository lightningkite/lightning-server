package com.lightningkite.lightningserver.terraform.awsec2

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsEc2BuilderBase
import com.lightningkite.lightningserver.terraform.aws.ec2.StigLevel
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsScalingEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.stigBuildLinux
import com.lightningkite.lightningserver.terraform.aws.ec2.TerraformAwsSingleEc2Builder
import com.lightningkite.lightningserver.terraform.aws.ec2.VpcInfoTerraformManaged
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.gibibytes
import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.terraformJsonObject
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
        fulfillSetting(engineCache.name, Json.encodeToJsonElement(Cache.Settings()))
        fulfillSetting(enginePubSub.name, Json.encodeToJsonElement(PubSub.Settings()))
    }

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
        override val applicationVpc = terraformManagedVPC(
            ipPrefix = "10.0",
            availabilityZones = listOf("us-west-2a", "us-west-2b"),
            natGateway = AwsVpc.NatGateway.Single,
        )
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
        override val applicationVpc = terraformManagedVPC(
            ipPrefix = "10.0",
            availabilityZones = listOf("us-west-2a", "us-west-2b"),
            natGateway = AwsVpc.NatGateway.Single,
        )
        override val scalingRequestsPerTarget = 300
        override val maxInstanceLifetimeSeconds = 604800
        override val terraformRoot = File(tmpRoot, "online")
        override fun OnlineServer.settings() = fulfillGlobals()
    }

    inner class SwapSingleDeployment : TerraformAwsSingleEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Swap Single Test"
        override val domainZone = "example.com"
        override val domain = "swap-single.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = AwsVpc.Default
        override val swapSize: DataSize? = 2.gibibytes
        override val swapSwappiness = 30
        override val terraformRoot = File(tmpRoot, "swap-single")
        override fun TestServer.settings() = fulfillGlobals(cacheUrl = null)
    }

    inner class SwapScalingDeployment : TerraformAwsScalingEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Swap Scaling Test"
        override val domainZone = "example.com"
        override val domain = "swap-scaling.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = terraformManagedVPC(
            ipPrefix = "10.0",
            availabilityZones = listOf("us-west-2a", "us-west-2b"),
            natGateway = AwsVpc.NatGateway.Single,
        )
        override val swapSize: DataSize? = 2.gibibytes
        override val terraformRoot = File(tmpRoot, "swap-scaling")
        override fun TestServer.settings() = fulfillGlobals("redis://cache:6379")
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
        override val applicationVpc = terraformManagedVPC(
            ipPrefix = "10.0",
            availabilityZones = listOf("us-west-2a", "us-west-2b"),
            natGateway = AwsVpc.NatGateway.Single,
        )
        override val customerManagedKey = true
        override val wafRules: List<JsonObject> = listOf(
            terraformJsonObject {
                "name" - "AWSIPReputation"
                "priority" - 10
                "override_action" {
                    "none" {}
                }
                "statement" {
                    "managed_rule_group_statement" {
                        "vendor_name" - "AWS"
                        "name" - "AWSManagedRulesAmazonIpReputationList"
                    }
                }
                "visibility_config" {
                    "cloudwatch_metrics_enabled" - true
                    "metric_name" - "AWSIPReputation"
                    "sampled_requests_enabled" - true
                }
            }
        )
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
    fun swapConfiguredOnlyWhenRequested() {
        val plain = SingleDeployment()
        plain.write()
        val plainInit = File(plain.terraformRoot, "ec2_init.sh").readText()
        assertFalse(plainInit.contains("swap-setup"), "Swap must not be configured without swapSize")

        val single = SwapSingleDeployment()
        single.write()
        val init = File(single.terraformRoot, "ec2_init.sh").readText()
        assertContains(init, "SIZE_MIB=2048")
        assertContains(init, "vm.swappiness=30")
        // The unit is installed, enabled, and — on the single instance — started during boot, but
        // best-effort: ec2_init.sh runs under `set -e` and must not die over a missing safety net.
        assertContains(init, "systemctl enable swap-single-test-swap.service")
        assertContains(init, "systemctl start swap-single-test-swap.service || echo")
        // An existing correctly-sized file is re-enabled rather than rewritten on every reboot.
        assertContains(init, "Reusing existing")

        val scaling = SwapScalingDeployment()
        scaling.write()
        val component = File(scaling.terraformRoot, "image_data.yaml").readText()
        assertContains(component, "SIZE_MIB=2048")
        // Default swappiness, and the AMI bake only enables the unit; the file is allocated at boot.
        assertContains(component, "vm.swappiness=10")
        assertContains(component, "systemctl enable swap-scaling-test-swap.service")
        assertFalse(
            component.contains("systemctl start swap-scaling-test-swap.service"),
            "The AMI bake must not allocate the swap file",
        )
    }

    @Test
    fun swapLargerThanRootVolumeRejected() {
        val ex = assertFailsWith<IllegalArgumentException> {
            object : TerraformAwsSingleEc2Builder<TestServer>(TestServer) {
                override val storageBucket = "test-tf-state"
                override val region: Region = Region.US_WEST_2
                override val displayName = "Swap Too Big"
                override val domainZone = "example.com"
                override val domain = "swap-big.example.com"
                override val debug = true
                override val emergencyContact = EmailAddress("ops@example.com")
                override val instanceType = "t4g.medium"
                override val instanceArchitecture = CPUArchitecture.Arm
                override val applicationVpc = AwsVpc.Default
                override val volumeSizeGiB = 8
                override val swapSize: DataSize? = 16.gibibytes
                override val terraformRoot = File(tmpRoot, "swap-too-big")
                override fun TestServer.settings() = fulfillGlobals(cacheUrl = null)
            }.write()
        }
        assertContains(ex.message!!, "does not fit")
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

    // === Service hardening: layout, sandbox, settings handling ===

    /** A single-instance deployment with the service-hardening knobs exposed; one terraform root per [name]. */
    inner class ConfiguredSingleDeployment(
        name: String,
        override val serviceSandboxing: Boolean = true,
        override val serviceWritablePaths: List<String> = emptyList(),
        configFiles: Map<String, String> = emptyMap(),
    ) : TerraformAwsSingleEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Configured $name"
        override val domainZone = "example.com"
        override val domain = "$name.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = AwsVpc.Default
        override val terraformRoot = File(tmpRoot, "configured-$name")
        override fun TestServer.settings() = fulfillGlobals(cacheUrl = null)

        init {
            configFiles.forEach { (fileName, content) -> instanceFilesRaw[fileName to FileType.Config] = content }
        }
    }

    inner class ConfiguredScalingDeployment(
        name: String,
    ) : TerraformAwsScalingEc2Builder<TestServer>(TestServer) {
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Configured Scaling $name"
        override val domainZone = "example.com"
        override val domain = "scaling-$name.example.com"
        override val debug = true
        override val emergencyContact = EmailAddress("ops@example.com")
        override val instanceType = "t4g.medium"
        override val instanceArchitecture = CPUArchitecture.Arm
        override val applicationVpc = terraformManagedVPC(
            ipPrefix = "10.0",
            availabilityZones = listOf("us-west-2a", "us-west-2b"),
            natGateway = AwsVpc.NatGateway.Single,
        )
        override val terraformRoot = File(tmpRoot, "configured-scaling-$name")
        override fun TestServer.settings() = fulfillGlobals("redis://cache:6379")
    }

    private val cipherArgs = "-aes-256-cbc -pbkdf2 -iter 10000 -md sha256"

    private fun File.triggersOf(nullResource: String): JsonObject =
        findResource("null_resource", nullResource)!!["triggers"]!!.jsonObject

    /**
     * The single instance's `ec2_init.sh` goes through terraform `templatefile()`, so every `${...}` in it
     * must be a template variable. A leaked Kotlin interpolation (or an unescaped shell `${...}`) would
     * fail at plan time; this catches it at generation time instead. `$${...}` is the template escape.
     */
    private fun assertOnlyTemplateVariables(script: String, allowed: Set<String> = setOf("deployment_bucket")) {
        val used = Regex("""(?<!\$)\$\{([^}]*)}""").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet(), used - allowed, "Unexpected template expressions in ec2_init.sh")
        assertFalse(Regex("""(?<!%)%\{""").containsMatchIn(script), "Unexpected template directive in ec2_init.sh")
    }

    @Test
    fun serviceUsesStandardLayoutAndSandbox() {
        val d = SingleDeployment()
        d.write()
        val init = File(d.terraformRoot, "ec2_init.sh").readText()
        assertOnlyTemplateVariables(init)

        // Unprivileged user, homed in its state directory; code root-owned, config root:<user>.
        assertContains(init, "useradd --system --user-group --home-dir /var/lib/single-test --no-create-home --shell /usr/sbin/nologin lightning-server")
        assertContains(init, "install -d -o root -g root -m 0755 /opt/single-test")
        assertContains(init, "install -d -o root -g lightning-server -m 0750 /etc/single-test")
        assertContains(init, "install -d -o lightning-server -g lightning-server -m 0700 /var/lib/single-test")

        // The unit: state dir as working directory, settings bind-mounted read-only, sandbox on.
        for (line in listOf(
            "User=lightning-server",
            "WorkingDirectory=/var/lib/single-test",
            "ExecStart=/opt/single-test/server/bin/server serve",
            "StateDirectory=single-test",
            "CacheDirectory=single-test",
            "LogsDirectory=single-test",
            "BindReadOnlyPaths=/etc/single-test/settings.enc:/var/lib/single-test/settings.json",
            "NoNewPrivileges=true",
            "UMask=0077",
            "ProtectSystem=strict",
            "ProtectHome=true",
            "PrivateTmp=true",
            "ProtectProc=invisible",
            "RestrictNamespaces=true",
        )) assertTrue(Regex("(?m)^" + Regex.escape(line) + "$").containsMatchIn(init), "Missing unit line: $line")
        assertFalse(init.contains("ReadWritePaths="), "No extra writable paths by default")
        assertFalse(init.contains("AmbientCapabilities"), "No capabilities for an unprivileged port")
        assertFalse(init.contains("MemoryDenyWriteExecute"), "Breaks the JVM's JIT")
        assertFalse(init.contains("ubuntu:ubuntu") || init.contains("User=ubuntu"), "The app must not run as ubuntu")

        // The redeploy keeps the build root-owned and never hands it to the service.
        assertContains(init, "chown -R root:root \"\$APP_DIR\"")
        assertFalse(init.contains("chown -R lightning-server:lightning-server \"\$APP_DIR\""))
        // Predeploy scratch is an unguessable mktemp dir, not a fixed path.
        assertContains(init, "mktemp -d \"/var/tmp/single-test-predeploy.XXXXXX\"")
        assertFalse(init.contains("/opt/lightning-server/predeploy"))
    }

    @Test
    fun sandboxCanBeDisabledOrExtended() {
        val off = ConfiguredSingleDeployment("nosandbox", serviceSandboxing = false)
        off.write()
        val offInit = File(off.terraformRoot, "ec2_init.sh").readText()
        assertFalse(offInit.contains("ProtectSystem="), "Sandbox directives must be absent when disabled")
        // Privilege and settings protections do not depend on the sandbox switch.
        assertContains(offInit, "NoNewPrivileges=true")
        assertContains(offInit, "BindReadOnlyPaths=/etc/configured-nosandbox/settings.enc:")

        val extended = ConfiguredSingleDeployment("rwpaths", serviceWritablePaths = listOf("/srv/uploads", "/var/spool/app"))
        extended.write()
        assertContains(File(extended.terraformRoot, "ec2_init.sh").readText(), "ReadWritePaths=/srv/uploads /var/spool/app")
    }

    @Test
    fun configInstanceFilesReadableOnlyByServiceUser() {
        val d = ConfiguredSingleDeployment("cfg", configFiles = mapOf("app.conf" to "key=value"))
        d.write()
        val init = File(d.terraformRoot, "ec2_init.sh").readText()
        assertContains(init, "chown root:lightning-server /etc/configured-cfg")
        assertContains(init, "chmod 750 /etc/configured-cfg")
        assertContains(init, "chown root:lightning-server '/etc/configured-cfg/app.conf'")
        assertContains(init, "chmod 640 '/etc/configured-cfg/app.conf'")
    }


    @Test
    fun settingsAtRestNeverPlaintext() {
        val single = ConfiguredSingleDeployment("enc")
        single.write()
        val init = File(single.terraformRoot, "ec2_init.sh").readText()
        assertOnlyTemplateVariables(init)
        // The encrypted file is what gets mounted; systemd fetches the key as root before each start.
        assertContains(init, "BindReadOnlyPaths=/etc/configured-enc/settings.enc:/var/lib/configured-enc/settings.json")
        assertContains(init, "ExecStartPre=+/usr/local/bin/configured-enc-settings-key")
        // '-' is required: systemd loads the file before ExecStartPre too, which is what creates it.
        assertContains(init, "EnvironmentFile=-/run/configured-enc-secrets/settings.env")
        // The root key step must not trust the service-writable HOME or working directory.
        assertContains(init, "export HOME=/root AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null")
        // Decryption is only ever a test to /dev/null; plaintext is never written.
        assertContains(init, "-out /dev/null -pass env:SETTINGS_PASS")
        assertFalse(init.contains("settings.json.new"), "Plaintext settings must never be written")
        // Predeploy passes the password through the environment, never argv, and not via sudo (which resets it).
        assertContains(init, "LIGHTNING_SERVER_SETTINGS_DECRYPTION=\"\$SETTINGS_PASS\" runuser -u lightning-server -- env")
        assertFalse(init.contains("sudo -u lightning-server env"))
        // Every re-encryption (e.g. a rotated password) must reach the instance.
        val singleTriggers = single.terraformRoot.triggersOf("redeploy_app")
        assertEquals("\${null_resource.upload_settings.id}", singleTriggers["settings_upload"]!!.jsonPrimitive.content)
        assertNull(singleTriggers["settings_hash"])

        val scaling = ConfiguredScalingDeployment("enc")
        scaling.write()
        val component = File(scaling.terraformRoot, "image_data.yaml").readText()
        assertContains(component, "ExecStartPre=+/usr/local/bin/configured-scaling-enc-settings-key")
        assertContains(component, "cat > /usr/local/bin/configured-scaling-enc-settings-key")
        assertFalse(component.contains("settings.json.new"), "Plaintext settings must never be written")
        val scalingTriggers = scaling.terraformRoot.triggersOf("redeploy_app")
        assertEquals("\${null_resource.upload_settings.id}", scalingTriggers["settings_upload"]!!.jsonPrimitive.content)
        assertNull(scalingTriggers["settings_hash"])
    }

    @Test
    fun settingsReencryptAndReuploadWhenAnyInputChanges() {
        val d = SingleDeployment()
        d.write()
        val encrypt = d.terraformRoot.triggersOf("encrypt_settings")
        assertNotNull(encrypt["settings_hash"])
        assertEquals(cipherArgs, encrypt["cipher"]!!.jsonPrimitive.content)
        assertEquals("\${sha256(random_password.settings.result)}", encrypt["password_hash"]!!.jsonPrimitive.content)
        // Upload is chained to the encrypt step, not to the raw settings.
        val upload = d.terraformRoot.triggersOf("upload_settings")
        assertEquals("\${null_resource.encrypt_settings.id}", upload["encrypted"]!!.jsonPrimitive.content)
        assertNull(upload["settings_hash"])
        // Encryption and on-instance decryption use the same cipher arguments.
        val terraform = d.terraformRoot.listFiles { f -> f.name.endsWith(".tf.json") }!!.joinToString("\n") { it.readText() }
        assertContains(terraform, "openssl enc $cipherArgs -in")
        assertContains(File(d.terraformRoot, "ec2_init.sh").readText(), "openssl enc -d $cipherArgs")
    }
}
