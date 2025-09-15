package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.sessions.EstablishOtp
import com.lightningkite.lightningserver.sessions.EstablishPassword
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

public object LiveProofClientEndpoints {
    public open class Sms(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.Sms {
        override suspend fun beginSmsOwnershipProof(input: String): String = fetcher(
            url = "$subpath/start",
            method = HttpMethod.POST,
            inSerializer = String.serializer(),
            body = input,
            outSerializer = String.serializer(),
        )

        override suspend fun provePhoneOwnership(input: FinishProof): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = FinishProof.serializer(),
            body = input,
            outSerializer = Proof.serializer(),
        )
    }

    public open class Email(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.Email {
        override suspend fun beginEmailOwnershipProof(input: String): String = fetcher(
            url = "$subpath/start",
            method = HttpMethod.POST,
            inSerializer = String.serializer(),
            body = input,
            outSerializer = String.serializer()
        )

        override suspend fun proveEmailOwnership(input: FinishProof): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = FinishProof.serializer(),
            body = input,
            outSerializer = Proof.serializer()
        )
    }

    public open class TimeBasedOTP(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.TimeBasedOTP {
        override suspend fun proveOTP(input: IdentificationAndPassword): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = IdentificationAndPassword.serializer(),
            body = input,
            outSerializer = Proof.serializer()
        )

        override suspend fun establishOneTimePassword(input: EstablishOtp): String = fetcher(
            url = "$subpath/establish",
            method = HttpMethod.POST,
            inSerializer = EstablishOtp.serializer(),
            body = input,
            outSerializer = String.serializer()
        )

        override suspend fun confirmOneTimePassword(input: String): Unit = fetcher(
            url = "$subpath/existing",
            method = HttpMethod.POST,
            inSerializer = String.serializer(),
            body = input,
            outSerializer = Unit.serializer()
        )
    }

    public open class Password(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.Password {
        override suspend fun provePasswordOwnership(input: IdentificationAndPassword): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = IdentificationAndPassword.serializer(),
            body = input,
            outSerializer = Proof.serializer()
        )

        override suspend fun establishPassword(input: EstablishPassword): Unit = fetcher(
            url = "$subpath/establish",
            method = HttpMethod.POST,
            inSerializer = EstablishPassword.serializer(),
            body = input,
            outSerializer = Unit.serializer()
        )
    }

    public open class BackupCode(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.BackupCode {
        override suspend fun proveBackupCode(input: IdentificationAndPassword): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = IdentificationAndPassword.serializer(),
            body = input,
            outSerializer = Proof.serializer()
        )

        override suspend fun resetCodes(): List<String> = fetcher(
            url = "$subpath/reset-codes",
            method = HttpMethod.POST,
            inSerializer = Unit.serializer(),
            body = Unit,
            outSerializer = ListSerializer(String.serializer())
        )

        override suspend fun clearCodes(): Unit = fetcher(
            url = "$subpath/clear-codes",
            method = HttpMethod.POST,
            inSerializer = Unit.serializer(),
            body = Unit,
            outSerializer = Unit.serializer(),
        )

        override suspend fun established(): Boolean = fetcher(
            url = "$subpath/established",
            method = HttpMethod.GET,
            inSerializer = Unit.serializer(),
            body = Unit,
            outSerializer = Boolean.serializer()
        )
    }

    public open class WebAuthNEndpoints(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.WebAuthN {
        override suspend fun start(input: Identification): WebAuthN.Authentication.StartResponse =
            fetcher(
                url = "$subpath/start",
                method = HttpMethod.POST,
                inSerializer = Identification.serializer(),
                body = input,
                outSerializer = WebAuthN.Authentication.StartResponse.serializer()
            )

        override suspend fun prove(input: WebAuthN.Authentication.ProveRequest): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = WebAuthN.Authentication.ProveRequest.serializer(),
            body = input,
            outSerializer = Proof.serializer()
        )

        override suspend fun registerStart(input: WebAuthN.GeneralPreference): WebAuthN.Registration.RegistrationResponse =
            fetcher(
                url = "$subpath/register-start",
                method = HttpMethod.POST,
                inSerializer = WebAuthN.GeneralPreference.serializer(),
                body = input,
                outSerializer = WebAuthN.Registration.RegistrationResponse.serializer()
            )

        override suspend fun registerFinish(input: WebAuthN.Registration.RegisterRequest): Unit = fetcher(
            url = "$subpath/register-finish",
            method = HttpMethod.POST,
            inSerializer = WebAuthN.Registration.RegisterRequest.serializer(),
            body = input,
            outSerializer = Unit.serializer(),
        )
    }

    public open class KnownDevice(
        public val fetcher: Fetcher,
        public val subpath: String,
    ) : ProofClientEndpoints.KnownDevice {
        override suspend fun proveKnownDevice(input: String): Proof = fetcher(
            url = "$subpath/prove",
            method = HttpMethod.POST,
            inSerializer = String.serializer(),
            body = input,
            outSerializer = Proof.serializer()
        )

        override suspend fun knownDeviceOptions(): KnownDeviceOptions = fetcher(
            url = "$subpath/options",
            method = HttpMethod.GET,
            inSerializer = Unit.serializer(),
            body = Unit,
            outSerializer = KnownDeviceOptions.serializer()
        )

        override suspend fun establishKnownDevice(): String = fetcher(
            url = "$subpath/establish",
            method = HttpMethod.POST,
            inSerializer = Unit.serializer(),
            body = Unit,
            outSerializer = String.serializer()
        )

        override suspend fun establishKnownDeviceV2(): KnownDeviceSecretAndExpiration = fetcher(
            url = "$subpath/establish2",
            method = HttpMethod.POST,
            inSerializer = Unit.serializer(),
            body = Unit,
            outSerializer = KnownDeviceSecretAndExpiration.serializer()
        )
    }
}