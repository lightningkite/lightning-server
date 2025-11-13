package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.services.terraform.TerraformNeed
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import java.io.File
import java.security.SecureRandom
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.swing.JOptionPane
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Source for retrieving deployment secrets like API keys, passwords, and credentials.
 *
 * Implementations can retrieve secrets from various locations:
 * - Environment variables ([EnvironmentSecretSource])
 * - Encrypted files ([EncryptedFileSecretSource])
 * - Cloud secret managers (AWS Secrets Manager, etc.)
 * - Multiple sources with fallback ([ManySecretSources])
 */
public interface SecretSource {
    /** Display name for this secret source (e.g., "environment", "~/.secrets/prod.json.enc") */
    public val name: String

    /**
     * Retrieves the value for a required secret.
     * @throws IllegalStateException if the secret is not found and no fallback is available
     */
    public fun <T> get(need: TerraformNeed<T>): T =
        getOrNull(need) ?: throw IllegalStateException("Missing secret ${need.name}, no backup methodology established")

    /**
     * Retrieves the value for a secret, or null if not found.
     * @return The secret value, or null if not available in this source
     */
    public fun <T> getOrNull(need: TerraformNeed<T>): T?
}

/**
 * A [SecretSource] that can interactively prompt the user for missing secrets.
 * Typically used during development or initial deployment setup.
 */
public interface InteractiveSecretSource {
    /**
     * Prompts the user to provide a value for the given secret.
     * @return The value entered by the user
     */
    public fun <T> prompt(need: TerraformNeed<T>): T
}

/**
 * A [SecretSource] that can both retrieve and store secrets, with interactive prompting support.
 * When a secret is not found, it prompts the user and stores the entered value for future use.
 */
public interface PopulatableSecretSource : SecretSource, InteractiveSecretSource {
    /**
     * Stores a secret value in this source.
     */
    public fun <T> set(need: TerraformNeed<T>, value: T)

    /**
     * Gets a secret, prompting the user if not found.
     * The prompted value is automatically stored for future use.
     */
    override fun <T> get(need: TerraformNeed<T>): T {
        return getOrNull(need) ?: prompt(need)
    }

    /**
     * Default implementation that prompts via terminal/console input.
     * Displays instructions and handles default values.
     */
    override fun <T> prompt(need: TerraformNeed<T>): T {
        println("${need.name}: ${need.instructions}")
        println(
            "Enter value for '${need.name}'${
                need.default?.let {
                    " (leave blank to default to ${
                        need.serializer.emit(
                            it
                        )
                    })"
                } ?: ""
            }: ")
        val typed = readInput { secret -> if (secret.isBlank()) need.default!! else need.serializer.parse(secret) }
        set(need, typed)
        return typed
    }
}

/**
 * Helper function to present a numbered list of options and get user selection via terminal input.
 *
 * @param prompt The question to display to the user
 * @param options List of options to choose from
 * @param stringify Function to convert each option to a display string
 * @return The selected option
 */
internal fun <T> readSelection(prompt: String, options: List<T>, stringify: (T) -> String = { it.toString() }): T {
    println(prompt)
    for ((index, option) in options.withIndex()) {
        println("  ${index + 1}) ${stringify(option)}")
    }
    return readInput {
        val index =
            it.toIntOrNull() ?: throw IllegalArgumentException("Please enter a number between 1 and ${options.size}")
        if (index < 1 || index > options.size) throw IllegalArgumentException("Please enter a number between 1 and ${options.size}")
        options[index - 1]
    }
}

/**
 * Opens a GUI (Swing-based) editor for viewing and modifying secrets.
 * Useful on desktop environments with GUI support.
 *
 * @param variables List of secrets that can be edited
 */
public fun SecretSource.runGuiEditor(variables: List<TerraformNeed<*>>) {
    if (variables.isEmpty()) return

    // Build a simple Swing-based loop to select and edit variables
    val nameToNeed = variables.associateBy { it.name }
    val pop = this as? PopulatableSecretSource
    val interactive = this as? InteractiveSecretSource

    while (true) {
        fun emitAny(serializer: KSerializer<Any?>, value: Any): String {
            return if (serializer.descriptor.kind is PrimitiveKind)
                StringArrayFormat(EmptySerializersModule()).encodeToString(serializer, value)
            else Json.encodeToString(serializer, value)
        }

        val optionLabels = (variables.map { v ->
            val current = try {
                this.getOrNull(v)
            } catch (_: Exception) {
                null
            }
            val currentStr = try {
                @Suppress("UNCHECKED_CAST")
                current?.let { emitAny((v as TerraformNeed<Any?>).serializer as KSerializer<Any?>, it as Any) }
            } catch (_: Exception) {
                null
            }
            if (currentStr != null) "${v.name} (set)" else v.name
        } + listOf("Quit")).toTypedArray()

        val selection = JOptionPane.showInputDialog(
            null,
            "Select a secret to view/edit:",
            "Secrets - ${this.name}",
            JOptionPane.QUESTION_MESSAGE,
            null,
            optionLabels,
            optionLabels.first()
        ) as? String ?: return

        if (selection == "Quit") return
        val selectedNeed = nameToNeed[selection.substringBefore(" ")] ?: continue

        // If we can directly populate, prompt for value; otherwise delegate to interactive prompt.
        if (pop != null) {
            while (true) {
                val currentVal = try {
                    this.getOrNull(selectedNeed)
                } catch (_: Exception) {
                    null
                }
                val currentStr = try {
                    @Suppress("UNCHECKED_CAST")
                    (selectedNeed as TerraformNeed<Any?>).serializer.let { s ->
                        currentVal?.let {
                            (s as KSerializer<Any?>).emit(
                                it
                            )
                        }
                    }
                } catch (_: Exception) {
                    null
                }

                val message = buildString {
                    append("${selectedNeed.name}: ${selectedNeed.instructions}\n")
                    if (currentStr != null) append("Current: $currentStr\n")
                    selectedNeed.default?.let { def ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val defStr = emitAny(
                                (selectedNeed as TerraformNeed<Any?>).serializer as KSerializer<Any?>,
                                def as Any
                            )
                            append("Leave blank to default to $defStr\n")
                        } catch (_: Exception) {
                        }
                    }
                }

                val input = JOptionPane.showInputDialog(null, message, currentStr ?: "") ?: break
                try {
                    @Suppress("UNCHECKED_CAST")
                    val typed = if (input.isBlank()) {
                        if (selectedNeed.default != null) selectedNeed.default as Any
                        else throw IllegalArgumentException("A value is required")
                    } else {
                        (selectedNeed as TerraformNeed<Any?>).serializer.parse(input) as Any
                    }
                    @Suppress("UNCHECKED_CAST")
                    pop.set(selectedNeed as TerraformNeed<Any>, typed as Any)
                    JOptionPane.showMessageDialog(null, "Saved '${selectedNeed.name}' to ${pop.name}.")
                    break
                } catch (e: IllegalArgumentException) {
                    JOptionPane.showMessageDialog(
                        null,
                        e.message ?: "Invalid value",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        null,
                        e.message ?: "Failed to save",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        } else if (interactive != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                interactive.prompt(selectedNeed as TerraformNeed<Any?>)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    null,
                    e.message ?: "Failed to prompt for value",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } else {
            // Not editable; just show the current value
            val currentVal = try {
                this.getOrNull(selectedNeed)
            } catch (_: Exception) {
                null
            }
            val currentStr = try {
                @Suppress("UNCHECKED_CAST")
                currentVal?.let {
                    emitAny(
                        (selectedNeed as TerraformNeed<Any?>).serializer as KSerializer<Any?>,
                        it as Any
                    )
                } ?: "<unset>"
            } catch (_: Exception) {
                "<unset>"
            }
            JOptionPane.showMessageDialog(null, "${selectedNeed.name} = $currentStr\n(Source: ${this.name})")
        }
    }
}

/**
 * Opens a terminal-based interactive editor for viewing and modifying secrets.
 * Only works if this SecretSource implements [InteractiveSecretSource].
 *
 * Provides a simple menu to:
 * - Select which variable to work with
 * - Read the current value
 * - Write a new value
 *
 * @param variables List of secrets that can be edited
 */
public fun SecretSource.runTerminalEditor(variables: List<TerraformNeed<*>>) {
    if (this !is InteractiveSecretSource) return
    while (true) {
        val sel = readSelection("What variable do you want to work with?", variables + null, { it?.name ?: "Quit" })
        if (sel == null) break
        val read = readSelection(
            "Do you want to read or write this variable?",
            listOf(true, false),
            { if (it) "Read" else "Write" })
        if (read) println("The current value is '${getOrNull(sel)}'.")
        else prompt(sel)
    }
}

/**
 * A [SecretSource] that checks multiple sources in order, returning the first found value.
 * Useful for implementing fallback chains like: environment variables → encrypted file → prompt user.
 *
 * When prompting for a new value, if multiple sources support storage ([PopulatableSecretSource]),
 * the user is asked where to store the value.
 *
 * Example:
 * ```
 * val secrets = ManySecretSources(
 *     EnvironmentSecretSource,
 *     EncryptedFileSecretSource("production"),
 * )
 * ```
 */
public class ManySecretSources(
    public val sources: List<SecretSource>,
) : SecretSource, InteractiveSecretSource {
    public constructor(vararg sources: SecretSource) : this(sources.toList())

    override val name: String = "Many"

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return sources.firstNotNullOfOrNull { it.getOrNull(need) }
    }

    override fun <T> prompt(need: TerraformNeed<T>): T {
        val options = sources.filterIsInstance<PopulatableSecretSource>()
        println("${need.name}: ${need.instructions}")
        return when (options.size) {
            0 -> throw IllegalStateException("Missing secret ${need.name}, no backup methodology established")
            1 -> {
                val destination = options.single()
                println(
                    "Enter value for '${need.name}' to be stored in ${destination.name}${
                        need.default?.let {
                            " (leave blank to default to ${
                                need.serializer.emit(
                                    it
                                )
                            })"
                        } ?: ""
                    }: ")
                val typed =
                    readInput { secret -> if (secret.isBlank()) need.default!! else need.serializer.parse(secret) }
                destination.set(need, typed)
                typed
            }

            else -> {
                println()
                val destination = readSelection("Where would you like to store '${need.name}'?", options) { it.name }
                println(
                    "Enter value for '${need.name}' to be stored in ${destination.name}${
                        need.default?.let {
                            " (leave blank to default to ${
                                need.serializer.emit(
                                    it
                                )
                            })"
                        } ?: ""
                    }: ")
                val typed =
                    readInput { secret -> if (secret.isBlank()) need.default!! else need.serializer.parse(secret) }
                destination.set(need, typed)
                typed
            }
        }
    }

    override fun <T> get(need: TerraformNeed<T>): T {
        return getOrNull(need) ?: prompt(need)
    }
}

/**
 * Parses a string into the type represented by this serializer.
 * Handles both primitive types (using StringArrayFormat) and complex types (using JSON).
 *
 * @throws IllegalArgumentException if the string doesn't match the expected format
 */
private fun <T> KSerializer<T>.parse(string: String): T = try {
    if (this.descriptor.kind is PrimitiveKind)
        StringArrayFormat(EmptySerializersModule()).decodeFromString(this, string)
    else
        Json.decodeFromString(this, string)
} catch (e: SerializationException) {
    throw IllegalArgumentException("Does not fit format.  Expecting a ${this.descriptor.serialName}")
}

/**
 * Serializes a value to a string representation.
 * Handles both primitive types (using StringArrayFormat) and complex types (using JSON).
 */
private fun <T> KSerializer<T>.emit(value: T): String =
    if (this.descriptor.kind is PrimitiveKind) StringArrayFormat(EmptySerializersModule()).encodeToString(
        this,
        value
    )
    else Json.encodeToString(this, value)

/**
 * Reads input from stdin and processes it, retrying on IllegalArgumentException.
 * Useful for interactive terminal prompts with validation.
 *
 * @param process Function to validate and transform the input string
 * @return The processed result
 */
internal fun <T> readInput(process: (String) -> T): T {
    while (true) {
        val input = readlnOrNull()
        try {
            return process(input ?: "")
        } catch (e: IllegalArgumentException) {
            println("Try again: " + e.message)
        }
    }
}

/**
 * A [SecretSource] that reads secrets from environment variables.
 * Secrets are expected to be in the format `LS_SECRET_{NAME}`.
 *
 * Example: For a secret named "AWS_ACCESS_KEY_ID", this looks for an environment
 * variable named "LS_SECRET_AWS_ACCESS_KEY_ID".
 *
 * This is useful for CI/CD environments where secrets are injected as environment variables.
 */
public object EnvironmentSecretSource : SecretSource {
    override val name: String = "environment"
    override fun <T> getOrNull(need: TerraformNeed<T>): T? =
        System.getenv("LS_SECRET_${need.name}")?.let { need.serializer.parse(it) }
}

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

                val password = passwordFetcher.read("Enter your secrets password for $file:") { pwd ->
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
                    passwordFetcher.read("Pick your secrets password for $file:") { /* no verification needed */ }
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

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return getMap()[need.name]?.let { json.decodeFromString(need.serializer, it) }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        getMap()[need.name] = json.encodeToString(need.serializer, value)
        saveEncrypted()
    }
}


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

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return getWraps().get(need)
    }

}

/*
 * TODO: API Recommendations for SecretSource
 *
 * 2. PasswordFetcher.read() stores passwords in plain String, which can't be zeroed from memory. Consider
 *    using CharArray or ByteArray that can be explicitly cleared for better security.
 *
 *
 * 4. The runGuiEditor and runTerminalEditor functions are useful but tightly coupled to Swing/console.
 *    Consider abstracting the UI layer to support different environments (headless servers, web UIs, etc.).
 *
 * 5. Consider adding validation/type checking when setting secrets to catch errors early (e.g., detecting
 *    if a value can be parsed as the expected type before storing it).
 *
 * 6. The PBKDF2 iteration count (100,000) is reasonable for 2025 but may need adjustment over time.
 *    Consider making it configurable or gradually increasing it in future versions.
 */