package com.lightningkite.lightningserver

/**
 * Marks the OAuth 2.1 authorization server and OpenID Connect provider (`OpenIdProviderEndpoints` in `sessions-oauth`)
 * and the types that exist only for it.
 */
@RequiresOptIn(
    "The OAuth authorization server has not been verified against the OAuth and OpenID Connect specifications or " +
        "with real clients. It may change or be removed.",
    RequiresOptIn.Level.ERROR,
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalOauthProviderApi
