package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.EstablishPassword
import com.lightningkite.lightningserver.sessions.EstablishTotp
import com.lightningkite.lightningserver.typed.LiveVersion

/**
 * Provides the client endpoints of a proof method, each of which can establish a credential and prove it.
 *
 * Establishing a credential requires existing authentication, while proving one is part of logging in.
 */
public sealed interface ProofClientEndpoints {
    /** Returns the identifier of this method, such as `sms`, `email` or `password`. */
    public val via: String

    /** Returns the user property this method verifies, such as `phone` or `email`, or `null` if it verifies none, as with a password or TOTP. */
    public val property: String? get() = null

    /** Proves ownership of a phone number with a PIN sent by SMS. */
    @LiveVersion(LiveProofClientEndpoints.Sms::class)
    public interface Sms : ProofClientEndpoints {
        override val via: String get() = "sms"
        override val property: String? get() = "phone"

        /** Sends a PIN to the phone number [input] and returns the temporary key to pass to [provePhoneOwnership]. */
        public suspend fun beginSmsOwnershipProof(input: String): String

        /** Returns a signed [Proof] of phone ownership for the PIN and key in [input]. */
        public suspend fun provePhoneOwnership(input: FinishProof): Proof
    }

    /** Proves ownership of an email address with a PIN or magic link sent by email. */
    @LiveVersion(LiveProofClientEndpoints.Email::class)
    public interface Email : ProofClientEndpoints {
        override val via: String get() = "email"
        override val property: String get() = "email"

        /** Sends a PIN or link to the email address [input] and returns the temporary key to pass to [proveEmailOwnership]. */
        public suspend fun beginEmailOwnershipProof(input: String): String

        /** Returns a signed [Proof] of email ownership for the code and key in [input]. */
        public suspend fun proveEmailOwnership(input: FinishProof): Proof
    }

    /** Proves identity with a TOTP code from an authenticator app such as Google Authenticator, Authy or Microsoft Authenticator. */
    @LiveVersion(LiveProofClientEndpoints.TimeBasedOTP::class)
    public interface TimeBasedOTP : ProofClientEndpoints {
        override val via: String get() = "totp"

        /** Returns a signed [Proof] for the TOTP code in [input]. */
        public suspend fun proveOTP(input: IdentificationAndPassword): Proof

        /** Returns the `otpauth://` URL or QR code data URL for adding a new TOTP secret for the authenticated user to an authenticator app. */
        public suspend fun establishOneTimePassword(input: EstablishTotp): String

        /** Activates the TOTP secret for the authenticated user if [input] is a valid code from the newly configured authenticator app. */
        public suspend fun confirmOneTimePassword(input: String)
    }

    /** Proves identity with a password. */
    @LiveVersion(LiveProofClientEndpoints.Password::class)
    public interface Password : ProofClientEndpoints {
        override val via: String get() = "password"

        /** Returns a signed [Proof] for the identification and password in [input]. */
        public suspend fun provePasswordOwnership(input: IdentificationAndPassword): Proof

        /** Sets or changes the authenticated user's password. */
        public suspend fun establishPassword(input: EstablishPassword)
    }

    /** Proves identity with single-use backup codes, as a fallback when another method such as a TOTP device is unavailable. */
    @LiveVersion(LiveProofClientEndpoints.BackupCode::class)
    public interface BackupCode : ProofClientEndpoints {
        override val via: String get() = "backupcode"

        /** Returns a signed [Proof] for the backup code in [input], which can then not be used again. */
        public suspend fun proveBackupCode(input: IdentificationAndPassword): Proof

        /** Returns new backup codes for the authenticated user, which invalidate any previous ones and should be saved securely. */
        public suspend fun resetCodes(): List<String>

        /** Removes all backup codes of the authenticated user. */
        public suspend fun clearCodes()

        /** Returns `true` if the authenticated user has backup codes. */
        public suspend fun established(): Boolean
    }

    /** Proves identity with WebAuthn, using security keys or platform authenticators such as Touch ID, Face ID or Windows Hello. */
    @LiveVersion(LiveProofClientEndpoints.WebAuthNEndpoints::class)
    public interface WebAuthN : ProofClientEndpoints {
        override val via: String get() = "WebAuthN"

        /** Returns the challenge and options for a WebAuthn authentication ceremony, optionally identifying the subject with a proof from another method in [input]. */
        public suspend fun start(input: WebAuthN.Authentication.StartRequest): WebAuthN.Authentication.StartResponse

        /** Returns a signed [Proof] for the challenge ID and authenticator assertion in [input]. */
        public suspend fun prove(input: WebAuthN.Authentication.ProveRequest): Proof

        /** Returns the challenge and options for registering a WebAuthn credential for the authenticated user, given their resident key preference. */
        public suspend fun registerStart(input: WebAuthN.GeneralPreference): WebAuthN.Registration.RegistrationResponse

        /** Registers the credential attested in [input] for the authenticated user. */
        public suspend fun registerFinish(input: WebAuthN.Registration.RegisterRequest)
    }

    /** Allows weaker authentication on previously trusted devices. */
    @LiveVersion(LiveProofClientEndpoints.KnownDevice::class)
    public interface KnownDevice : ProofClientEndpoints {
        override val via: String get() = "known-device"

        /** Returns the duration and strength of known device proofs. */
        public suspend fun knownDeviceOptions(): KnownDeviceOptions

        /** Returns a signed [Proof], with reduced strength, for the device secret [input] from [establishKnownDevice]. */
        public suspend fun proveKnownDevice(input: String): Proof

        /**
         * Registers this device as known for the authenticated user and returns the device secret, which the client must store securely.
         *
         * Prefer [establishKnownDeviceV2].
         */
        public suspend fun establishKnownDevice(): String

        /** Registers this device as known for the authenticated user and returns the device secret and its expiration, which the client must store securely. */
        public suspend fun establishKnownDeviceV2(): KnownDeviceSecretAndExpiration
    }
}
