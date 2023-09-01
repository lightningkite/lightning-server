package com.lightningkite.lightningserver.auth.oauth

import kotlinx.serialization.Serializable


/**
 * You can set up a new app for GitHub in your [developer settings](https://github.com/settings/developers).
 * Get the client ID and a client secret to put into your [setting] parameter.
 * Return URLs are your auth url + /oauth/github/callback
 *
 * You can set up a new Google project in the [Google console](https://console.cloud.google.com)
 * Fill out the [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent)
 * Enable the non-sensitive scopes for '.../auth/userinfo.email' and '.../auth/userinfo.profile'
 * Add an [OAuth 2.0 Client ID](https://console.cloud.google.com/apis/credentials/oauthclient)
 * 'Authorized redirect URIs' are your auth url + /oauth/google/callback
 *
 * You can set up a Microsoft sign-in app in the [Azure Console's Active Directory section](https://portal.azure.com/#view/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/~/RegisteredApps)
 * Note your 'Application (client) ID'.  You'll put that into [setting] as the [OauthProviderCredentials.id].
 * In the API Permissions section, add the permissions 'email' and 'User.Read'.
 * In the Certificates & secrets section, create a new client secret.  Copy out the value and put it into [setting] as the [OauthProviderCredentials.secret].
 *
 */
@Serializable
data class OauthProviderCredentials(
    val id: String,
    val secret: String
)

