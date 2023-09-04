package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.old.BaseAuthEndpoints
import com.lightningkite.lightningserver.auth.old.EmailAuthEndpoints
import com.lightningkite.lightningserver.auth.old.SmsAuthEndpoints
import com.lightningkite.lightningserver.auth.old.PasswordAuthEndpoints
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.SecureHasherSettings

@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias BaseAuthEndpoints<USER, ID> = BaseAuthEndpoints<USER, ID>
@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias EmailAuthEndpoints<USER, ID> = EmailAuthEndpoints<USER, ID>
@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias SmsAuthEndpoints<USER, ID> = SmsAuthEndpoints<USER, ID>
@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias PasswordAuthEndpoints<USER, ID> = PasswordAuthEndpoints<USER, ID>
@Deprecated("JwtSigner has been fully deprecated in favor of using SecureHasher.", ReplaceWith("SecureHasherSettings", "com.lightningkite.lightningserver.encryption.SecureHasher"))
typealias JwtSigner = SecureHasher
@Deprecated("AuthInfo has been replaced with AuthOptions", ReplaceWith("AuthOptions"))
typealias AuthInfo<T> = AuthOptions
@Deprecated("AuthInfo has been replaced with AuthRequirement", ReplaceWith("AuthRequirement()"))
inline fun <reified T> AuthInfo(): AuthOptions = authOptions<T>()
