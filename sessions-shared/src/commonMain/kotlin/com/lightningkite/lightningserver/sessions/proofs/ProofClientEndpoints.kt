package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.EstablishTotp
import com.lightningkite.lightningserver.sessions.EstablishPassword
import com.lightningkite.lightningserver.typed.LiveVersion

public sealed interface ProofClientEndpoints {
    public val via: String
    public val property: String? get() = null

    @LiveVersion(LiveProofClientEndpoints.Sms::class)
    public interface Sms : ProofClientEndpoints {
        override val via: String get() = "sms"
        override val property: String? get() = "phone"

        public suspend fun beginSmsOwnershipProof(input: String): String
        public suspend fun provePhoneOwnership(input: FinishProof): Proof
    }

    @LiveVersion(LiveProofClientEndpoints.Email::class)
    public interface Email : ProofClientEndpoints {
        override val via: String get() = "email"
        override val property: String get() = "email"

        public suspend fun beginEmailOwnershipProof(input: String): String
        public suspend fun proveEmailOwnership(input: FinishProof): Proof
    }

    @LiveVersion(LiveProofClientEndpoints.TimeBasedOTP::class)
    public interface TimeBasedOTP : ProofClientEndpoints {
        override val via: String get() = "totp"

        public suspend fun proveOTP(input: IdentificationAndPassword): Proof

        // required auth
        public suspend fun establishOneTimePassword(input: EstablishTotp): String
        public suspend fun confirmOneTimePassword(input: String)
    }

    @LiveVersion(LiveProofClientEndpoints.Password::class)
    public interface Password : ProofClientEndpoints {
        override val via: String get() = "password"

        public suspend fun provePasswordOwnership(input: IdentificationAndPassword): Proof

        // requires auth
        public suspend fun establishPassword(input: EstablishPassword)
    }

    @LiveVersion(LiveProofClientEndpoints.BackupCode::class)
    public interface BackupCode : ProofClientEndpoints {
        override val via: String get() = "backupcode"

        public suspend fun proveBackupCode(input: IdentificationAndPassword): Proof

        // requires auth
        public suspend fun resetCodes(): List<String>
        public suspend fun clearCodes()
        public suspend fun established(): Boolean
    }

    @LiveVersion(LiveProofClientEndpoints.WebAuthNEndpoints::class)
    public interface WebAuthN : ProofClientEndpoints {
        override val via: String get() = "WebAuthN"

        public suspend fun start(input: Identification): WebAuthN.Authentication.StartResponse
        public suspend fun prove(input: WebAuthN.Authentication.ProveRequest): Proof

        // requires auth
        public suspend fun registerStart(input: WebAuthN.GeneralPreference): WebAuthN.Registration.RegistrationResponse
        public suspend fun registerFinish(input: WebAuthN.Registration.RegisterRequest)
    }

    @LiveVersion(LiveProofClientEndpoints.KnownDevice::class)
    public interface KnownDevice : ProofClientEndpoints {
        override val via: String get() = "known-device"

        public suspend fun knownDeviceOptions(): KnownDeviceOptions
        public suspend fun proveKnownDevice(input: String): Proof

        // requires auth
        public suspend fun establishKnownDevice(): String
        public suspend fun establishKnownDeviceV2(): KnownDeviceSecretAndExpiration
    }
}
