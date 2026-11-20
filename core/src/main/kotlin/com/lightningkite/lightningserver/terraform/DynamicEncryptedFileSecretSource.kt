package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import java.io.File
import java.util.Properties

/**
 * A Wrapper around [EncryptedFileSecretSource] that allows a runtime input for the location of the secrets file.
 * Without hard coding the file location into the VCS you can have multiple developers with deploy capabilities
 * work on a project.
 *
 * This does support keeping a cache of the location so you do not need to enter it every time.
 * The cache file 'local.secrets.properties' will be placed at the process root directory.
 * You must *NOT* check this file into Version Control Systems as it is for local deployment configurations.
 *
 * The cache fully supports multiple secret file locations. If you have multiple deployments you are making for a
 * project, each secrets location can be cached in the same file.
 *
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
 * @param name Display name for this secret source
 * @param passwordFetcher Helper for password prompting and caching
 * @param cacheLocation An option that will turn on caching the encrypted files location in a properties file at the
 * process directory. The cache file should NOT be stored in VCS.
 */
public class DynamicEncryptedFileSecretSource(
    override val name: String,
    private val passwordFetcher: PasswordFetcher = PasswordFetcher(),
    private val cacheLocation: Boolean = false,
) : PopulatableSecretSource {
    private var wraps: EncryptedFileSecretSource? = null

    private companion object {
        private const val fileName = "local.secretFiles.properties"
    }

    private fun getWraps(): EncryptedFileSecretSource {
        return wraps
            ?: run {
                if (cacheLocation) {
                    File(fileName)
                        .takeIf { it.exists() }
                        ?.let {
                            val props = Properties()
                            props.load(it.inputStream())
                            props.getProperty(name)
                        }
                        ?.takeIf { it.isNotBlank() }
                        ?.let { location ->
                            EncryptedFileSecretSource(File(location), name, passwordFetcher)
                        }
                } else null

            }
            ?: run {
                println("Enter the secrets file location for $name:")
                val location = readInput { it.ifBlank { throw IllegalArgumentException("Invalid file path") } }
                if (cacheLocation) {
                    val file = File(fileName)
                    val props = Properties()
                    if (file.exists())
                        props.load(file.inputStream())
                    props.setProperty(name, location)
                    props.store(
                        file.outputStream(),
                        """ This file must *NOT* be checked into Version Control Systems
 as it contains information specific to your local deployment configuration
  
 This file was written automatically by the DynamicEncryptedFileSecretSource
 
 Location of the secret files for your deploy environments.
""".trimMargin()
                    )
                }
                wraps = EncryptedFileSecretSource(File(location), name, passwordFetcher)
                wraps!!
            }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        return getWraps().set(need, value)
    }

    override fun <T> getBoxed(need: TerraformNeed<T>): Stored<T>? {
        return getWraps().getBoxed(need)
    }

}
