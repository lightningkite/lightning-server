package com.lightningkite.lightningserver.sessions.proofs.oauth

import kotlinx.serialization.Serializable


/**
 * Holds the client credentials for an OAuth provider.
 *
 * For GitHub, create an app in your [developer settings](https://github.com/settings/developers), with the return URL your auth URL + `/oauth/github/callback`. For Google, create a client ID in the [Google console](https://console.cloud.google.com/apis/credentials/oauthclient) with the redirect URI your auth URL + `/oauth/google/callback`, after filling out the [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent) and enabling the `userinfo.email` and `userinfo.profile` scopes.
 *
 * For Microsoft, register an app in the [Azure Console](https://portal.azure.com/#view/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/~/RegisteredApps) and add the `email` and `User.Read` API permissions. Its Application (client) ID is [id], and a client secret created under Certificates & secrets is [secret].
 */
@Serializable
public data class OauthProviderCredentials(
    val id: String,
    val secret: String,
)

