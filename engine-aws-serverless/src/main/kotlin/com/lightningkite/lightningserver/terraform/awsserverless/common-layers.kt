package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive
import software.amazon.awssdk.services.lambda.model.Architecture

/*
Eventually, I'd like to be able to conveniently include major tools like Image Magic and Ffmpeg with a single line.
 */