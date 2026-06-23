package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.services.terraform.AwsVpc

internal class VpcInfoTerraformManaged(
    val ipPrefix: String,
    val availabilityZones: List<String>,
    val natGateway: AwsVpc.NatGateway,
    override val id: String,
    override val securityGroup: String,
    override val privateSubnets: String,
    override val publicSubnets: String,
    override val applicationSubnet: String,
    override val natGatewayIps: String,
    override val cidr: String = "$ipPrefix.0.0/16",
) : AwsVpc.VpcInfo