package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.oauth.OauthProviderCredentials
import com.lightningkite.lightningserver.auth.oauth.OauthProviderInfo
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.EmailPersonalization
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.typed
import java.time.Instant
import java.util.*

//class OauthProofEndpoints(
//    path: ServerPath,
//    proofHasher: () -> SecureHasher,
//    val provider: OauthProviderInfo,
//    val credentials: OauthProviderCredentials,
//) : ServerPathGroup(path), Authentication.ExternalProofMethod {
//    over
//}