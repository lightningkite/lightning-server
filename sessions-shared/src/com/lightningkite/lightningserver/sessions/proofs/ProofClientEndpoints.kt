package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.EstablishPassword
import com.lightningkite.lightningserver.sessions.EstablishTotp
import com.lightningkite.lightningserver.typed.LiveVersion

/**
 * Sealed interface for different proof method client endpoints.
 * Each authentication method (SMS, Email, Password, etc.) implements this interface
 * to provide a consistent API for establishing and proving authentication credentials.
 *
 * The authentication flow typically has two phases:
 * 1. Establishing credentials (requires existing authentication)
 * 2. Proving credentials (used during login)
 */
public sealed interface ProofClientEndpoints {
    /**
     * Unique identifier for this authentication method (e.g., "sms", "email", "password").
     */
    public val via: String

    /**
     * The user property this proof method verifies (e.g., "phone", "email").
     * Null for methods that don't verify a specific user property (e.g., password, totp).
     */
    public val property: String? get() = null

    /**
     * SMS-based authentication using phone number verification.
     * Sends a PIN code via SMS that the user must enter to prove ownership.
     */
    @LiveVersion(LiveProofClientEndpoints.Sms::class)
    public interface Sms : ProofClientEndpoints {
        override val via: String get() = "sms"
        override val property: String? get() = "phone"

        /**
         * Begin SMS ownership verification by sending a PIN to the phone number.
         *
         * @param input Phone number to verify (e.g., "+15555551234")
         * @return Temporary key/identifier to use when completing the proof
         */
        public suspend fun beginSmsOwnershipProof(input: String): String

        /**
         * Complete SMS ownership verification by providing the PIN received via SMS.
         *
         * @param input Contains the key from begin step and the PIN code
         * @return Signed proof that can be used for authentication
         */
        public suspend fun provePhoneOwnership(input: FinishProof): Proof
    }

    /**
     * Email-based authentication using email address verification.
     * Sends a magic link or PIN code via email that the user must use to prove ownership.
     */
    @LiveVersion(LiveProofClientEndpoints.Email::class)
    public interface Email : ProofClientEndpoints {
        override val via: String get() = "email"
        override val property: String get() = "email"

        /**
         * Begin email ownership verification by sending a PIN/link to the email address.
         * @param input Email address to verify
         * @return Temporary key/identifier to use when completing the proof
         */
        public suspend fun beginEmailOwnershipProof(input: String): String

        /**
         * Complete email ownership verification by providing the PIN/code received via email.
         * @param input Contains the key from begin step and the verification code
         * @return Signed proof that can be used for authentication
         */
        public suspend fun proveEmailOwnership(input: FinishProof): Proof
    }

    /**
     * Time-based One-Time Password (TOTP) authentication using authenticator apps.
     * Compatible with Google Authenticator, Authy, Microsoft Authenticator, etc.
     */
    @LiveVersion(LiveProofClientEndpoints.TimeBasedOTP::class)
    public interface TimeBasedOTP : ProofClientEndpoints {
        override val via: String get() = "totp"

        /**
         * Prove authentication using a TOTP code from an authenticator app.
         * @param input User identification and the 6-digit TOTP code
         * @return Signed proof that can be used for authentication
         */
        public suspend fun proveOTP(input: IdentificationAndPassword): Proof

        /**
         * Establish TOTP for the authenticated user (requires existing auth).
         * Returns a QR code URL or secret that can be added to an authenticator app.
         * @param input TOTP configuration (label, etc.)
         * @return QR code data URL or otpauth:// URL for the authenticator app
         */
        public suspend fun establishOneTimePassword(input: EstablishTotp): String

        /**
         * Confirm TOTP setup by verifying a code from the newly configured authenticator.
         * @param input The TOTP code from the authenticator app
         */
        public suspend fun confirmOneTimePassword(input: String)
    }

    /**
     * Traditional password-based authentication.
     */
    @LiveVersion(LiveProofClientEndpoints.Password::class)
    public interface Password : ProofClientEndpoints {
        override val via: String get() = "password"

        /**
         * Authenticate using username and password.
         * @param input User identification and password
         * @return Signed proof that can be used for authentication
         */
        public suspend fun provePasswordOwnership(input: IdentificationAndPassword): Proof

        /**
         * Set or change password for the authenticated user (requires existing auth).
         * @param input New password and optional hint
         */
        public suspend fun establishPassword(input: EstablishPassword)
    }

    /**
     * Backup codes for account recovery when primary authentication methods are unavailable.
     * Typically used as a fallback when TOTP device is lost or SMS is unavailable.
     */
    @LiveVersion(LiveProofClientEndpoints.BackupCode::class)
    public interface BackupCode : ProofClientEndpoints {
        override val via: String get() = "backupcode"

        /**
         * Authenticate using a backup code (single-use).
         * @param input User identification and backup code
         * @return Signed proof that can be used for authentication
         */
        public suspend fun proveBackupCode(input: IdentificationAndPassword): Proof

        /**
         * Generate new backup codes for the authenticated user (requires existing auth).
         * Invalidates any previous backup codes.
         * @return List of new backup codes that should be saved securely by the user
         */
        public suspend fun resetCodes(): List<String>

        /**
         * Remove all backup codes for the authenticated user (requires existing auth).
         */
        public suspend fun clearCodes()

        /**
         * Check if backup codes are currently established for the authenticated user.
         * @return True if backup codes exist, false otherwise
         */
        public suspend fun established(): Boolean
    }

    /**
     * WebAuthn-based authentication using hardware keys or platform authenticators.
     * Supports FIDO2 security keys, Touch ID, Face ID, Windows Hello, etc.
     */
    @LiveVersion(LiveProofClientEndpoints.WebAuthNEndpoints::class)
    public interface WebAuthN : ProofClientEndpoints {
        override val via: String get() = "WebAuthN"

        /**
         * Start WebAuthn authentication by getting a challenge.
         * @param input User identification
         * @return Challenge and options for the WebAuthn authentication ceremony
         */
        public suspend fun start(input: Identification): WebAuthN.Authentication.StartResponse

        /**
         * Complete WebAuthn authentication by verifying the signed challenge.
         * @param input Challenge ID and signed credentials from the authenticator
         * @return Signed proof that can be used for authentication
         */
        public suspend fun prove(input: WebAuthN.Authentication.ProveRequest): Proof

        /**
         * Start WebAuthn credential registration (requires existing auth).
         * @param input Resident key preference
         * @return Challenge and options for the WebAuthn registration ceremony
         */
        public suspend fun registerStart(input: WebAuthN.GeneralPreference): WebAuthN.Registration.RegistrationResponse

        /**
         * Complete WebAuthn credential registration (requires existing auth).
         * @param input Challenge ID and attestation from the authenticator
         */
        public suspend fun registerFinish(input: WebAuthN.Registration.RegisterRequest)
    }

    /**
     * Known device authentication ("Remember this device").
     * Allows weaker authentication on previously trusted devices.
     */
    @LiveVersion(LiveProofClientEndpoints.KnownDevice::class)
    public interface KnownDevice : ProofClientEndpoints {
        override val via: String get() = "known-device"

        /**
         * Get known device configuration (duration, strength).
         * @return Configuration for known device proofs
         */
        public suspend fun knownDeviceOptions(): KnownDeviceOptions

        /**
         * Authenticate using a known device secret.
         * @param input Device secret obtained from establishKnownDevice
         * @return Signed proof that can be used for authentication (with reduced strength)
         */
        public suspend fun proveKnownDevice(input: String): Proof

        /**
         * Legacy: Register this device as known for the authenticated user (requires existing auth).
         * Prefer establishKnownDeviceV2 for new implementations.
         * @return Device secret that should be stored securely on the client
         */
        public suspend fun establishKnownDevice(): String

        /**
         * Register this device as known for the authenticated user (requires existing auth).
         * @return Device secret and expiration time that should be stored securely on the client
         */
        public suspend fun establishKnownDeviceV2(): KnownDeviceSecretAndExpiration
    }
}
