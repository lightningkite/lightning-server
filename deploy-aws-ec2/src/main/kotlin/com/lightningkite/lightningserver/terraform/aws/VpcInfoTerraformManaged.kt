package com.lightningkite.lightningserver.terraform.aws

import com.lightningkite.services.terraform.AwsVpc
import com.lightningkite.services.terraform.TerraformJsonObject

public class VpcInfoTerraformManaged(
    public val ipPrefix: String,
    public val availabilityZones: List<String>,
    public val natGateway: AwsVpc.NatGateway,
) : AwsVpc.VpcInfo {
    override val id: String = TerraformJsonObject.expression("module.vpc.vpc_id")
    override val securityGroup: String = TerraformJsonObject.expression("aws_security_group.internal.id")
    override val privateSubnets: String = TerraformJsonObject.expression("module.vpc.private_subnets")
    override val publicSubnets: String = TerraformJsonObject.expression("module.vpc.public_subnets")
    override val applicationSubnet: String = TerraformJsonObject.expression("module.vpc.public_subnets[0]")
    override val natGatewayIps: String = TerraformJsonObject.expression("module.vpc.nat_public_ips")
    override val cidr: String = "$ipPrefix.0.0/16"
}