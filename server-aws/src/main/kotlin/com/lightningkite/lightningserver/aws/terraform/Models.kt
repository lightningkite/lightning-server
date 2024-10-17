package com.lightningkite.lightningserver.aws.terraform

import kotlinx.serialization.Serializable


@Serializable
data class TerraformProjectInfo(
    val projectName: String,
    val bucket: String,
    val bucketPathOverride: String? = null,
    val core: TerraformCoreType = TerraformCoreType.Lambda,
    val vpc: Boolean = true,
    val existingVpc: Boolean = false,
    val domain: Boolean = true,
    val profile: String,
    val createBeforeDestroy: Boolean = false,
    val handlers: Map<String, String> = mapOf(),
) {
}

@Serializable
enum class TerraformCoreType {
    Lambda, SingleEC2, ELB
}

internal val TerraformProjectInfo.privateSubnets get() = if (existingVpc) "[for s in data.aws_subnet.private : s.id]" else "module.vpc.private_subnets"
internal val TerraformProjectInfo.subnet_cidr_blocks get() = if (existingVpc) "[for s in data.aws_subnet.private : s.cidr_block]" else "concat(module.vpc.private_subnets_cidr_blocks, module.vpc.private_subnets_cidr_blocks, [])"
internal val TerraformProjectInfo.vpc_id get() = if (existingVpc) "data.aws_vpc.main.id" else "module.vpc.vpc_id"
internal val TerraformProjectInfo.vpc_cidr_block get() = if (existingVpc) "data.aws_vpc.main.cidr_block" else "module.vpc.vpc_cidr_block"
internal val TerraformProjectInfo.public_route_table_ids get() = if (existingVpc) "toset([data.aws_vpc.main.main_route_table_id])" else "module.vpc.public_route_table_ids"
internal val TerraformProjectInfo.natGatewayIp get() = if (existingVpc) "[for s in data.aws_nat_gateway.main : s.public_ip]" else "module.vpc.nat_public_ips"

internal val TerraformProjectInfo.projectNameSafe: String
    get() = projectName.filter {
        it.isLetterOrDigit() || it in setOf(
            '-',
            '_'
        )
    }
internal val TerraformProjectInfo.namePrefix: String get() = projectNameSafe
internal val TerraformProjectInfo.namePrefixLower: String get() = projectNameSafe.lowercase()
internal val TerraformProjectInfo.namePrefixUnderscores: String get() = projectNameSafe.replace("-", "_")
internal val TerraformProjectInfo.namePrefixSafe: String get() = projectNameSafe.filter { it.isLetterOrDigit() }
internal val TerraformProjectInfo.namePrefixPath: String
    get() = projectNameSafe.lowercase().replace("-", "/").replace("_", "")
internal val TerraformProjectInfo.namePrefixPathSegment: String get() = projectNameSafe.lowercase().replace("_", "")