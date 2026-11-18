package com.lightningkite.lightningserver.http

/**
 * The client address a trusted reverse proxy recorded in its forwarding header (an engine's `realIpHeader`),
 * given that header's raw lines in order: the right-most entry.
 *
 * Proxies append the address they saw (nginx's `$proxy_add_x_forwarded_for`, an AWS ALB), so everything to
 * its left came from the client and can be forged.  A single-value header such as nginx's
 * `X-Real-IP $remote_addr` is returned as is.  Pass raw lines, not [HttpHeaderValue]s: their `;` parsing
 * would let a client cut off the proxy's entry.
 */
public fun proxiedSourceIp(headerLines: List<String>): String? =
    headerLines.lastOrNull()?.substringAfterLast(',')?.trim()
