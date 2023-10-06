package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.old.BaseAuthEndpoints
import com.lightningkite.lightningserver.auth.old.EmailAuthEndpoints
import com.lightningkite.lightningserver.auth.old.SmsAuthEndpoints
import com.lightningkite.lightningserver.auth.old.PasswordAuthEndpoints
import com.lightningkite.lightningserver.encryption.SecureHasher

@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias BaseAuthEndpoints<USER, ID> = BaseAuthEndpoints<USER, ID>
@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias EmailAuthEndpoints<USER, ID> = EmailAuthEndpoints<USER, ID>
@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias SmsAuthEndpoints<USER, ID> = SmsAuthEndpoints<USER, ID>
@Deprecated("New import; use not recommended.  Please use the newer auth system.  It will require refactors.")
typealias PasswordAuthEndpoints<USER, ID> = PasswordAuthEndpoints<USER, ID>

