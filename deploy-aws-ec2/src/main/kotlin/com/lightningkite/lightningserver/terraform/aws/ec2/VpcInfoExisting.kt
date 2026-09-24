package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.TerraformJsonObject

/**
 * A [AwsVpc.VpcInfo] for a VPC that already exists and is not managed by this Terraform.
 * [instanceSubnet] is only used by the single EC2 builder to place its instance.
 */
internal class VpcInfoExisting(
    override val id: String,
    override val cidr: String,
    override val securityGroup: String,
    override val privateSubnets: String,
    override val publicSubnets: String,
    override val applicationRouteTables: String,
    override val natGatewayIps: String,
    val instanceSubnet: String? = null,
) : AwsVpc.VpcInfo

/** Formats literal IDs as a Terraform list expression, e.g. `${["subnet-a", "subnet-b"]}`. */
internal fun List<String>.toTerraformList(): String =
    TerraformJsonObject.expression(joinToString(", ", "[", "]") { "\"$it\"" })
