package com.lightningkite.lightningserver.guide.samples

// region proof-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.PinBasedProofEndpoints.ScreeningResult
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.sms.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.uuid.*
// endregion proof-imports

// region account-model
@Queryable
data class Account(
    override val _id: Uuid = Uuid.random(),
    val email: EmailAddress,
    val phone: PhoneNumber? = null,
    val isAdmin: Boolean = false,
) : HasId<Uuid>
// endregion account-model

// region account-auth
object AccountAuth : PrincipalType<Account, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<Account> = Account.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): Account =
        LoginServer.accounts().get(id) ?: throw NotFoundException("No such account")

    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): Account? = when (property) {
        "email" -> {
            val email = value.toEmailAddress()
            LoginServer.accounts().findOne(Condition { it.email eq email })
                ?: LoginServer.accounts().insertOne(Account(email = email))
        }
        "phone" -> LoginServer.accounts().findOne(Condition { it.phone eq value.toPhoneNumber() })
        else -> super.fetchByProperty(property, value)
    }
}
// endregion account-auth

// region login-server
object LoginServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())

    val accounts = database.registerTable<Account>("Account")

    val pins = PinHandler(cache, "pins")

    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pin = pins,
        email = email,
        emailTemplate = { to, pin ->
            Email(subject = "Your login code", to = listOf(EmailAddressWithName(to)), plainText = "Your code is $pin.")
        },
    )
    val proofSms = path.path("proof").path("sms") module SmsProofEndpoints(pins, sms)
    val proofPassword = path.path("proof").path("password") module PasswordProofEndpoints(
        database, cache,
        evaluatePassword = { if (it.password.length < 12) throw BadRequestException("Use at least 12 characters.") },
    )
    val proofTotp = path.path("proof").path("totp") module TimeBasedOTPProofEndpoints(database, cache)
    val proofDevice = path.path("proof").path("device") module KnownDeviceProofEndpoints(database, cache)
    val proofBackupCode = path.path("proof").path("backup-code") module BackupCodeEndpoints(database, cache)

    val auth = path.path("auth") module object : AuthEndpoints<Account, Uuid>(
        principal = AccountAuth,
        database = database,
        cache = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: Account): Int = if (subject.isAdmin) 20 else 10

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: Account): Instant? = now() + 90.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: Account): Duration? = 30.days
    }

    init {
        registerBasicMediaTypeCoders()
    }
}
// endregion login-server

// region screened-email
object ScreenedEmailServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())

    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pin = PinHandler(cache, "pins"),
        email = email,
        emailTemplate = { to, pin ->
            Email(subject = "Your login code", to = listOf(EmailAddressWithName(to)), plainText = "Your code is $pin.")
        },
        screenEmail = { address ->
            when {
                address.raw.endsWith("@mailinator.com") ->
                    ScreeningResult.Reject("Please use a permanent email address.", detail = "disposable-email")
                address.raw.endsWith("@example.com") -> ScreeningResult.Suppress
                else -> ScreeningResult.Allow
            }
        },
    )
}
// endregion screened-email

// region auth-listener
object AuditedLoginServer : ServerBuilder() {
    val failedLogins = install(object : AuthEventListener {
        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent) {
            if (event is AuthEvent.ProofCredentialRejected)
                runtime.logger.warn { "Rejected ${event.method.via} proof for ${event.principal}" }
        }
    })

    // In a real app, email the user here, so they hear about changes they did not make.
    val factorNotices = install(object : AuthEventListener {
        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent) {
            if (event is AuthEvent.FactorChanged)
                runtime.logger.info { "${event.method.via} ${event.change} for ${event.principal}" }
        }
    })
}
// endregion auth-listener

// region email-login-test
fun emailLoginTest() = LoginServer.testBlocking(settings = {
    database set Database.Settings("ram")
    cache set Cache.Settings("ram")
    email set EmailService.Settings("test")
}) {
    // 1. Ask for a PIN.  The response is a lookup key, not the PIN.
    val key = proofEmail.start.test(null, "alice@example.com")

    // 2. The user reads the PIN from their inbox.
    val sent = (email() as TestEmailService).lastEmail()!!
    val pin = Regex("[A-Z]{6}").find(sent.plainText)!!.value

    // 3. Trade the key and PIN for a signed proof.
    val proof = proofEmail.prove.test(null, FinishProof(key = key, password = pin))

    // 4. Log in with the proof to get a refresh token.
    val refreshToken = auth.login.test(null, listOf(proof)).refreshToken!!

    // 5. Trade the refresh token for a short-lived access token, and use it.
    val accessToken = auth.tokenSimple.test(null, refreshToken)
    val response = auth.self.test(headers = HttpHeaders("Authorization" to "Bearer $accessToken"))
    check(response.status == HttpStatus.OK)
}
// endregion email-login-test

fun passwordLoginTest() = LoginServer.testBlocking(settings = {
    database set Database.Settings("ram")
    cache set Cache.Settings("ram")
}) {
    val alice = accounts().insertOne(Account(email = "alice@example.com".toEmailAddress()))
    val aliceAuth = AccountAuth.testAuth(alice)

    try {
        proofPassword.establish.test(aliceAuth, EstablishPassword("short"))
        error("A short password was accepted")
    } catch (e: BadRequestException) {
        // expected
    }
    proofPassword.establish.test(aliceAuth, EstablishPassword("correct horse battery"))

    val proof = proofPassword.prove.test(
        null,
        IdentificationAndPassword(type = "Account", property = "email", value = "alice@example.com", password = "correct horse battery"),
    )
    check(auth.login.test(null, listOf(proof)).refreshToken != null)

    // The same proof can't open a second session.
    try {
        auth.login.test(null, listOf(proof))
        error("A proof was accepted twice")
    } catch (e: HttpStatusException) {
        check(e.status.code == 400)
    }
}

fun adminNeedsTwoFactorsTest() = LoginServer.testBlocking(settings = {
    database set Database.Settings("ram")
    cache set Cache.Settings("ram")
    email set EmailService.Settings("test")
}) {
    val admin = accounts().insertOne(Account(email = "root@example.com".toEmailAddress(), isAdmin = true))
    proofPassword.establish.test(AccountAuth.testAuth(admin), EstablishPassword("correct horse battery"))

    val passwordProof = proofPassword.prove.test(
        null,
        IdentificationAndPassword(type = "Account", property = "email", value = "root@example.com", password = "correct horse battery"),
    )
    val partial = auth.login.test(null, listOf(passwordProof))
    check(partial.refreshToken == null)
    check(partial.strengthRequired == 20)

    val key = proofEmail.start.test(null, "root@example.com")
    val pin = Regex("[A-Z]{6}").find((email() as TestEmailService).lastEmail()!!.plainText)!!.value
    val emailProof = proofEmail.prove.test(null, FinishProof(key = key, password = pin))
    check(auth.login.test(null, listOf(passwordProof, emailProof)).refreshToken != null)
}

fun screenedEmailTest() = ScreenedEmailServer.testBlocking(settings = {
    cache set Cache.Settings("ram")
    email set EmailService.Settings("test")
}) {
    try {
        proofEmail.start.test(null, "someone@mailinator.com")
        error("A disposable address was accepted")
    } catch (e: HttpStatusException) {
        check(e.status.code == 400)
    }
    proofEmail.start.test(null, "someone@example.com")
    check((email() as TestEmailService).lastEmail() == null)
    proofEmail.start.test(null, "someone@lightningkite.com")
    check((email() as TestEmailService).lastEmail() != null)
}
