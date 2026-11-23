package com.lightningkite.lightningserver.http

/**
 * Returns the client address that a trusted reverse proxy recorded in [header] (an engine's `realIpHeader`), or `null`
 * if this has no [header].
 *
 * Proxies append the address they saw (nginx's `$proxy_add_x_forwarded_for`, an AWS ALB), so only the right-most entry
 * is used: everything to its left came from the client and can be forged.  A single-value header such as nginx's
 * `X-Real-IP $remote_addr` is returned as is.
 */
public fun HttpHeaders.getProxiedSourceIp(header: String): String? = getMany(header).lastOrNull()?.root?.trim()
