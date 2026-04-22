package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.terraform.*

public abstract class TerraformAwsServerlessDomainBuilder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsServerlessBuilder<S>(
    builder = builder,
), TerraformEmitterAwsDomain {

    public abstract val domainZone: String
    public abstract override val domain: String
    override val domainZoneId: String by lazy { domainZoneId(domainZone) }
}

public abstract class TerraformAwsServerlessVpcBuilder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsServerlessBuilder<S>(
    builder = builder,
), TerraformEmitterAwsVpc {
    public open val ipPrefix: String get() = "10.0"
    public open val availabilityZones: List<String>
        get() = listOf(
            "${region.id()}a",
            "${region.id()}b",
            "${region.id()}c"
        )
    override val applicationVpc: TerraformAwsVpcInfo by lazy { vpc(ipPrefix, availabilityZones) }
}

public abstract class TerraformAwsServerlessDomainVpcBuilder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsServerlessBuilder<S>(
    builder = builder,
), TerraformEmitterAwsVpc, TerraformEmitterAwsDomain {
    public abstract val domainZone: String
    public abstract override val domain: String
    override val domainZoneId: String by lazy { domainZoneId(domainZone) }

    public open val ipPrefix: String get() = "10.0"
    public open val availabilityZones: List<String>
        get() = listOf(
            "${region.id()}a",
            "${region.id()}b",
            "${region.id()}c"
        )
    override val applicationVpc: TerraformAwsVpcInfo by lazy { vpc(ipPrefix, availabilityZones) }
}


private fun TerraformEmitterAws.domainZoneId(domainZone: String): String {
    emit("cloud") {
        "data.aws_route53_zone.main" {
            "name" - domainZone
        }
    }
    return TerraformJsonObject.expression("data.aws_route53_zone.main.zone_id")
}


private fun TerraformEmitterAws.vpc(
    ipPrefix: String = "10.0",
    availabilityZones: List<String> = listOf("${applicationRegion}a", "${applicationRegion}b", "${applicationRegion}c"),
): TerraformAwsVpcInfo {
    val cidr = "$ipPrefix.0.0/16"
    emit("cloud") {
        "module.vpc" {
            "source" - "terraform-aws-modules/vpc/aws"
            "version" - "6.6.0"

            "name" - projectPrefix
            "cidr" - cidr

            "azs" - availabilityZones
            "private_subnets" - listOf("${ipPrefix}.1.0/24", "${ipPrefix}.2.0/24", "${ipPrefix}.3.0/24")
            "public_subnets" - listOf("${ipPrefix}.101.0/24", "${ipPrefix}.102.0/24", "${ipPrefix}.103.0/24")

            "enable_nat_gateway" - true
            "single_nat_gateway" - true
            "enable_vpn_gateway" - false
            "enable_dns_hostnames" - true
            "enable_dns_support" - true
        }
        "resource.aws_vpc_endpoint.s3" {
            "vpc_id" - expression("module.vpc.vpc_id")
            "service_name" - "com.amazonaws.${this@vpc.applicationRegion}.s3"
            "route_table_ids"  - expression("module.vpc.public_route_table_ids")
        }
        "resource.aws_vpc_endpoint.execute_api" {
            "vpc_id" - expression("module.vpc.vpc_id")
            "service_name" - "com.amazonaws.${this@vpc.applicationRegion}.execute-api"
            "security_group_ids" - listOf(expression("aws_security_group.execute_api.id"))
            "vpc_endpoint_type" - "Interface"
        }
        "resource.aws_vpc_endpoint.lambda_invoke" {
            "vpc_id" - expression("module.vpc.vpc_id")
            "service_name" - "com.amazonaws.${this@vpc.applicationRegion}.lambda"
            "security_group_ids" - listOf(expression("aws_security_group.lambda_invoke.id"))
            "vpc_endpoint_type" - "Interface"
        }
        "resource.aws_security_group.internal" {
            "name" - "$projectPrefix-private"
            "vpc_id" - expression("module.vpc.vpc_id")
        }
        "resource.aws_vpc_security_group_ingress_rule.freeInternal" {
            "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
            "security_group_id" - expression("aws_security_group.internal.id")
            "cidr_ipv4" - expression("each.key")
            "ip_protocol" - -1
        }
        "resource.aws_vpc_security_group_egress_rule.freeInternal" {
            "for_each" - expression("toset(module.vpc.private_subnets_cidr_blocks)")
            "security_group_id" - expression("aws_security_group.internal.id")
            "cidr_ipv4" - expression("each.key")
            "ip_protocol" - -1
        }
        "resource.aws_security_group.access_outside" {
            "name" - "$projectPrefix-access-outside"
            "vpc_id" - expression("module.vpc.vpc_id")
        }
        "resource.aws_vpc_security_group_egress_rule.access_outside" {
            "security_group_id" - expression("aws_security_group.access_outside.id")
            "ip_protocol" - "-1"
            "cidr_ipv4" - "0.0.0.0/0"
        }
        "resource.aws_security_group.execute_api" {
            "name" - "$projectPrefix-execute-api"
            "vpc_id" - expression("module.vpc.vpc_id")
        }
        "resource.aws_vpc_security_group_ingress_rule.execute_api" {
            "security_group_id" - expression("aws_security_group.execute_api.id")
            "ip_protocol" - "tcp"
            "from_port" - 443
            "to_port" - 443
            "cidr_ipv4" - expression("module.vpc.vpc_cidr_block")
        }
        "resource.aws_security_group.lambda_invoke" {
            "name" - "$projectPrefix-lambda-invoke"
            "vpc_id" - expression("module.vpc.vpc_id")
        }
        "resource.aws_vpc_security_group_ingress_rule.lambda_invoke" {
            "security_group_id" - expression("aws_security_group.lambda_invoke.id")
            "ip_protocol" - "tcp"
            "from_port" - 443
            "to_port" - 443
            "cidr_ipv4" - expression("module.vpc.vpc_cidr_block")
        }
    }
    return TerraformAwsVpcInfo(
        id = "module.vpc.vpc_id",
        securityGroup = "aws_security_group.internal.id",
        privateSubnets = "module.vpc.private_subnets",
        publicSubnets = "module.vpc.public_subnets",
        applicationSubnets = "module.vpc.public_subnets",
        natGatewayIps = "module.vpc.nat_public_ips",
        cidr = cidr
    )
}