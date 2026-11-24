package com.lightningkite.lightningserver.terraform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

/**
 * Turns the AWS SDK's and its HTTP client's logging down to `WARN`, unless the application set their levels itself.
 *
 * At `DEBUG` they log every request and header, which buries the secret editor's prompts.
 */
internal fun quietAwsSdkLogging() {
    for (name in listOf("org.apache.hc.client5", "software.amazon.awssdk")) {
        val logger = LoggerFactory.getLogger(name) as? Logger ?: continue
        if (logger.level == null) logger.level = Level.WARN
    }
}
