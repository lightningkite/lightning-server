package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.terraform.*
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression

public abstract class TerraformAwsServerlessDomainBuilder<S : ServerBuilder>(
    builder: S,
) : TerraformAwsServerlessBuilder<S>(
    builder = builder,
), TerraformEmitterAwsDomain {

    public abstract val domainZone: String
    public abstract override val domain: String
    override val domainZoneId: String by lazy { domainZoneId(domainZone) }
}


private fun TerraformEmitterAws.domainZoneId(domainZone: String): String {
    emit("cloud") {
        "data.aws_route53_zone.main" {
            "name" - domainZone
        }
    }
    return expression("data.aws_route53_zone.main.zone_id")
}
