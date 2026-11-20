package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.swing.JOptionPane
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Helper class for prompting and caching password input.
 * Once a password is entered, it's cached in memory for the session to avoid repeated prompts.
 *
 * **Security Note**: The password is stored in plain text in memory. Call [clear] to remove it.
 */
public open class PasswordFetcher() {
    private var present: String? = null

    /** Clears the cached password, forcing a re-prompt on next [read] call. */
    public fun clear() {
        present = null
    }

    /**
     * Prompts for a password, with optional verification.
     * If a password was previously entered, returns the cached value.
     *
     * Uses System.console() for hidden input if available, otherwise falls back to GUI prompt.
     *
     * @param prompt The message to display when prompting for password
     * @param verify Function to validate the password (e.g., by attempting decryption)
     * @return The validated password
     */
    public open fun read(prompt: String, verify: (String) -> Unit): String {
        return present ?: run {
            while (true) {
                println(prompt)
                val password =
                    System.console()?.readPassword()?.toString() ?: JOptionPane.showInputDialog(null, prompt, "")
                if (password == null) {
                    throw IllegalStateException("No Input Provided")
                }
                try {
                    verify(password)
                    present = password
                    break
                } catch (e: Exception) {
                    println("Invalid password: ${e.message}.  Try again:")
                }
            }
            present!!
        }
    }
}

/**
 * Format version for the encrypted file structure.
 * Allows for future format upgrades while maintaining backward compatibility.
 */
@Serializable
private data class EncryptedFileFormat(
    val version: Int = 2,
    val salt: String,
    val iterations: Int = 100_000,
    val encryptedData: String,
)

/**
 * A [PopulatableSecretSource] that stores secrets in an encrypted JSON file.
 * Secrets are encrypted using AES-256-CBC with a key derived from a password via PBKDF2.
 *
 * **Security Features**:
 * - AES-256 encryption
 * - PBKDF2 key derivation with 100,000 iterations
 * - Random salt per file (32 bytes)
 * - Password validation on file read (attempts decryption to verify)
 * - Creates ~/.lightningserver/{name}.json.enc if not specified
 * - Automatic migration from legacy format (version 1) on first write
 *
 * **File Format** (JSON):
 * ```json
 * {
 *   "version": 2,
 *   "salt": "<base64-encoded-salt>",
 *   "iterations": 100000,
 *   "encryptedData": "<base64-encoded-encrypted-json>"
 * }
 * ```
 *
 * **Security Notes**:
 * - This implementation provides strong encryption suitable for local development and testing
 * - For production deployments, consider using a dedicated secret manager (AWS Secrets Manager, HashiCorp Vault, etc.)
 * - The encrypted file should still be protected with filesystem permissions
 * - Password strength is critical - use a strong, unique password
 *
 * **Backward Compatibility**:
 * - Version 2 (current): PBKDF2 with salt
 * - Version 1 (legacy): SHA-256 without salt (auto-migrated on write)
 *
 * @param file The file to store encrypted secrets in
 * @param name Display name for this secret source
 * @param passwordFetcher Helper for password prompting and caching
 */
public class EncryptedFileSecretSource(
    private val file: File,
    override val name: String = file.toString(),
    private val passwordFetcher: PasswordFetcher = PasswordFetcher(),
) : PopulatableSecretSource {

    /**
     * Convenience constructor that creates a file at ~/.lightningserver/{name}.json.enc
     *
     * @param name Name for the secrets file (e.g., "production", "staging")
     */
    public constructor(name: String, passwordFetcher: PasswordFetcher = PasswordFetcher()) : this(
        File(System.getProperty("user.home"), ".lightningserver/$name.json.enc"),
        "~/.lightningserver/$name.json.enc",
        passwordFetcher
    )

    private val json = Json
    private val provider = CryptographyProvider.Default.get(AES.CBC)
    private var encryptionKey: AES.CBC.Key? = null
    private var salt: ByteArray? = null
    private val iterations = 100_000
    private var map: HashMap<String, String>? = null
    private var isLegacyFormat = false

    /**
     * Derives an encryption key from a password using PBKDF2.
     * Uses 100,000 iterations of PBKDF2-HMAC-SHA256 to derive a 256-bit key.
     *
     * @param password The password to derive the key from
     * @param salt The salt to use (32 bytes)
     * @return AES encryption key
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun deriveKey(password: String, salt: ByteArray): AES.CBC.Key {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return provider.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, keyBytes)
    }

    /**
     * Attempts to decrypt data using legacy SHA-256 key derivation (version 1).
     * This is for backward compatibility with older encrypted files.
     */
    private fun tryLegacyDecrypt(password: String, encryptedData: ByteArray): String? {
        return try {
            val hmac = CryptographyProvider.Default.get(SHA256)
            val keyBytes = hmac.hasher().hashBlocking(password.toByteArray(Charsets.UTF_8))
            val key = provider.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, keyBytes)
            key.cipher().decryptBlocking(encryptedData).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Lazily loads and decrypts the secrets map from the file.
     * Prompts for password on first access.
     * Automatically detects and handles both version 1 (legacy) and version 2 (current) formats.
     *
     * @return Mutable map of secret names to JSON-encoded values
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun getMap(): MutableMap<String, String> {
        return map ?: run {
            if (file.exists()) {
                val fileContent = file.readText()

                // Try to parse as version 2 format first
                val format = try {
                    json.decodeFromString<EncryptedFileFormat>(fileContent)
                } catch (e: Exception) {
                    // Legacy format (raw encrypted bytes, no JSON wrapper)
                    null
                }

                val password = passwordFetcher.read("Enter your secrets password for $name:") { pwd ->
                    // Verify password by attempting decryption
                    if (format != null) {
                        // Version 2 format
                        val saltBytes = Base64.decode(format.salt)
                        val encryptedBytes = Base64.decode(format.encryptedData)
                        val key = deriveKey(pwd, saltBytes)
                        key.cipher().decryptBlocking(encryptedBytes).toString(Charsets.UTF_8)
                    } else {
                        // Legacy format
                        val result = tryLegacyDecrypt(pwd, file.readBytes())
                        result ?: throw IllegalArgumentException("Invalid password or corrupted file")
                    }
                }

                // Decrypt with verified password
                val decryptedJson = if (format != null) {
                    // Version 2 format
                    val saltBytes = Base64.decode(format.salt)
                    val encryptedBytes = Base64.decode(format.encryptedData)
                    val key = deriveKey(password, saltBytes)
                    this.encryptionKey = key
                    this.salt = saltBytes
                    key.cipher().decryptBlocking(encryptedBytes).toString(Charsets.UTF_8)
                } else {
                    // Legacy format - mark for migration
                    isLegacyFormat = true
                    val decrypted = tryLegacyDecrypt(password, file.readBytes())!!

                    // Generate new salt for migration
                    val newSalt = ByteArray(32).apply { SecureRandom.getInstanceStrong().nextBytes(this) }
                    this.salt = newSalt
                    this.encryptionKey = deriveKey(password, newSalt)

                    println("Note: Migrating secrets file to new secure format (PBKDF2 with salt)")
                    decrypted
                }

                json.decodeFromString<HashMap<String, String>>(decryptedJson).also {
                    map = it
                    // If legacy, immediately save in new format
                    if (isLegacyFormat) {
                        saveEncrypted()
                        isLegacyFormat = false
                    }
                }
            } else {
                // Create new file with version 2 format
                val password =
                    passwordFetcher.read("Pick your secrets password for $name:") { /* no verification needed */ }
                val newSalt = ByteArray(32).apply { SecureRandom.getInstanceStrong().nextBytes(this) }
                this.salt = newSalt
                this.encryptionKey = deriveKey(password, newSalt)

                val newMap = HashMap<String, String>()
                map = newMap
                file.parentFile?.mkdirs()
                saveEncrypted()
                newMap
            }
        }
    }

    /**
     * Saves the current secrets map to disk in encrypted format (version 2).
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun saveEncrypted() {
        val encryptedBytes = encryptionKey!!.cipher().encryptBlocking(
            json.encodeToString(map).toByteArray(Charsets.UTF_8)
        )

        val format = EncryptedFileFormat(
            version = 2,
            salt = Base64.encode(salt!!),
            iterations = iterations,
            encryptedData = Base64.encode(encryptedBytes)
        )

        file.writeText(json.encodeToString(format))
    }

    override fun <T> getBoxed(need: TerraformNeed<T>): Stored<T>? {
        return getMap()[need.name]?.let { Stored(json.decodeFromString(need.serializer, it)) }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        getMap()[need.name] = json.encodeToString(need.serializer, value)
        saveEncrypted()
    }
}
