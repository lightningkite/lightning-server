package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.services.terraform.TerraformNeed
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.properties.Properties
import java.io.File
import javax.swing.JOptionPane
import kotlin.collections.HashMap

public interface SecretSource {
    public val name: String
    public fun <T> get(need: TerraformNeed<T>): T = getOrNull(need) ?: throw IllegalStateException("Missing secret ${need.name}, no backup methodology established")
    public fun <T> getOrNull(need: TerraformNeed<T>): T?
}
public interface InteractiveSecretSource {
    public fun <T> prompt(need: TerraformNeed<T>): T
}
public interface PopulatableSecretSource: SecretSource, InteractiveSecretSource {
    public fun <T> set(need: TerraformNeed<T>, value: T)
    override fun <T> get(need: TerraformNeed<T>): T {
        return getOrNull(need) ?: prompt(need)
    }

    override fun <T> prompt(need: TerraformNeed<T>): T {
        println("${need.name}: ${need.instructions}")
        println("Enter value for '${need.name}'${need.default?.let { " (leave blank to default to ${need.serializer.emit(it)})" } ?: ""}: ")
        val typed = readInput { secret -> if(secret.isBlank()) need.default!! else need.serializer.parse(secret) }
        set(need, typed)
        return typed
    }
}

internal fun <T> readSelection(prompt: String, options: List<T>, stringify: (T)->String = { it.toString() }): T {
    println(prompt)
    for((index, option) in options.withIndex()) {
        println("  ${index+1}) ${stringify(option)}")
    }
    return readInput {
        val index = it.toIntOrNull() ?: throw IllegalArgumentException("Please enter a number between 1 and ${options.size}")
        if(index < 1 || index > options.size) throw IllegalArgumentException("Please enter a number between 1 and ${options.size}")
        options[index-1]
    }
}

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
            val current = try { this.getOrNull(v) } catch (_: Exception) { null }
            val currentStr = try {
                @Suppress("UNCHECKED_CAST")
                current?.let { emitAny((v as TerraformNeed<Any?>).serializer as KSerializer<Any?>, it as Any) }
            } catch (_: Exception) { null }
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
                val currentVal = try { this.getOrNull(selectedNeed) } catch (_: Exception) { null }
                val currentStr = try {
                    @Suppress("UNCHECKED_CAST")
                    (selectedNeed as TerraformNeed<Any?>).serializer.let { s -> currentVal?.let { (s as KSerializer<Any?>).emit(it) } }
                } catch (_: Exception) { null }

                val message = buildString {
                    append("${selectedNeed.name}: ${selectedNeed.instructions}\n")
                    if (currentStr != null) append("Current: $currentStr\n")
                    selectedNeed.default?.let { def ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val defStr = emitAny((selectedNeed as TerraformNeed<Any?>).serializer as KSerializer<Any?>, def as Any)
                            append("Leave blank to default to $defStr\n")
                        } catch (_: Exception) {}
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
                    JOptionPane.showMessageDialog(null, e.message ?: "Invalid value", "Error", JOptionPane.ERROR_MESSAGE)
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(null, e.message ?: "Failed to save", "Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        } else if (interactive != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                interactive.prompt(selectedNeed as TerraformNeed<Any?>)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(null, e.message ?: "Failed to prompt for value", "Error", JOptionPane.ERROR_MESSAGE)
            }
        } else {
            // Not editable; just show the current value
            val currentVal = try { this.getOrNull(selectedNeed) } catch (_: Exception) { null }
            val currentStr = try {
                @Suppress("UNCHECKED_CAST")
                currentVal?.let { emitAny((selectedNeed as TerraformNeed<Any?>).serializer as KSerializer<Any?>, it as Any) } ?: "<unset>"
            } catch (_: Exception) { "<unset>" }
            JOptionPane.showMessageDialog(null, "${selectedNeed.name} = $currentStr\n(Source: ${this.name})")
        }
    }
}

public fun SecretSource.runTerminalEditor(variables: List<TerraformNeed<*>>) {
    if(this !is InteractiveSecretSource) return
    while(true) {
        val sel = readSelection("What variable do you want to set?", variables + null, { it?.name ?: "Quit" })
        if(sel == null) break
        prompt(sel)
    }
}

public class LkSecretSource(public val env: String, public val passwordFetcher: PasswordFetcher = PasswordFetcher()): PopulatableSecretSource {
    override val name: String
        get() = "LK"

    private fun <R> keymaker(action: (pass: String) -> R): R {
        while(true) {
            try {
                val pass = passwordFetcher.read("Keymaker password:") {}
                return action(pass)
            } catch(e: Exception) {
                throw IllegalArgumentException("Failed to read secrets: ${e.message}", e)
            }
        }
    }

    public val loaded: MutableMap<String, String> by lazy {
        val out = StringBuilder()
        keymaker { pass ->
            out.clear()
            ProcessBuilder("sshpass", "-p", pass, "ssh", "keymaker", "readsecrets", env, "LS Deployer")
                .start()
                .also {
                    Thread {
                        it.inputStream.reader().use {
                            while (true) {
                                val c = it.read()
                                if (c == -1) break
                                out.append(c.toChar())
                            }
                        }
                    }.start()
                }
                .waitFor()
        }
        java.util.Properties().apply { load(out.toString().reader()) }.toMap().toMutableMap() as MutableMap<String, String>
    }
    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return loaded[need.name]?.let { need.serializer.parse(it) }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        loaded[need.name] = need.serializer.emit(value)
        keymaker { pass ->
            ProcessBuilder("sshpass", "-p", pass, "ssh", "keymaker", "replacesecrets", env, "LS Deployer")
                .start()
                .also {
                    it.outputStream.bufferedWriter().use {
                        java.util.Properties().apply {
                            putAll(loaded)
                        }.store(it, null)
                    }
                }
                .waitFor()
        }
    }
}

public class ManySecretSources(
    public val sources: List<SecretSource>
): SecretSource, InteractiveSecretSource {
    public constructor(vararg sources: SecretSource): this(sources.toList())
    override val name: String = "Many"

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return sources.firstNotNullOfOrNull { it.getOrNull(need) }
    }

    override fun <T> prompt(need: TerraformNeed<T>): T {
        val options = sources.filterIsInstance<PopulatableSecretSource>()
        println("${need.name}: ${need.instructions}")
        return when(options.size) {
            0 -> throw IllegalStateException("Missing secret ${need.name}, no backup methodology established")
            1 -> {
                val destination = options.single()
                println("Enter value for '${need.name}' to be stored in ${destination.name}${need.default?.let { " (leave blank to default to ${need.serializer.emit(it)})" } ?: ""}: ")
                val typed = readInput { secret -> if(secret.isBlank()) need.default!! else need.serializer.parse(secret) }
                destination.set(need, typed)
                typed
            }
            else -> {
                println()
                val destination = readSelection("Where would you like to store '${need.name}'?", options) { it.name }
                println("Enter value for '${need.name}' to be stored in ${destination.name}${need.default?.let { " (leave blank to default to ${need.serializer.emit(it)})" } ?: ""}: ")
                val typed = readInput { secret -> if(secret.isBlank()) need.default!! else need.serializer.parse(secret) }
                destination.set(need, typed)
                typed
            }
        }
    }

    override fun <T> get(need: TerraformNeed<T>): T {
        return getOrNull(need) ?: prompt(need)
    }
}

private fun <T> KSerializer<T>.parse(string: String): T = try {
    if (this.descriptor.kind is PrimitiveKind) StringArrayFormat(EmptySerializersModule()).decodeFromString(
        this,
        string
    )
    else Json.decodeFromString(this, string)
} catch(e: SerializationException) {
    throw IllegalArgumentException("Does not fit format.  Expecting a ${this.descriptor.serialName}")
}
private fun <T> KSerializer<T>.emit(value: T): String =
    if (this.descriptor.kind is PrimitiveKind) StringArrayFormat(EmptySerializersModule()).encodeToString(
        this,
        value
    )
    else Json.encodeToString(this, value)

internal fun <T> readInput(process: (String)->T): T {
    while(true) {
        val input = readlnOrNull()
        try {
            return process(input ?: "")
        } catch(e: IllegalArgumentException) {
            println("Try again: " + e.message)
        }
    }
}

public fun main(vararg args: String) {
    println("Masked test.")
    val r = readlnMasked()
    println("OK")
    println("Got $r")
//    val source = ManySecretSources(
//        EnvironmentSecretSource,
//        LkSecretSource("test")
//        EncryptedFileSecretSource("ivieleague-deployment-states_demo-example")
//        EncryptedFileSecretSource(File("local/samplesecrets.json.enc"))
//    )
//    source.runTerminalEditor(listOf(
//        TerraformNeed<String>("string"),
//        TerraformNeed<Int>("int"),
//    ))
//    source.get(TerraformNeed<String>("SomeStrangeSecret")).let { println(it) }
}

public object EnvironmentSecretSource: SecretSource {
    override val name: String = "environment"
    override fun <T> getOrNull(need: TerraformNeed<T>): T? = System.getenv("SECRET_${need.name}")?.let { need.serializer.parse(it) }
}

public class PasswordFetcher() {
    private var present: String? = null
    public fun clear() { present = null }
    public fun read(prompt: String, verify: (String)->Unit): String {
        return present ?: run {
            while(true) {
                println(prompt)
                val password = System.console()?.readPassword()?.toString() ?: JOptionPane.showInputDialog(null, prompt, "")
                try {
                    verify(password)
                    present = password
                    break
                } catch(e: Exception) {
                    println("Invalid password: ${e.message}.  Try again:")
                }
            }
            present!!
        }
    }
}

internal fun readlnMasked(): String {
    return System.console()?.readPassword()?.toString() ?: run {
//        var keepGoing = true
//        val masker = Thread {
//            while(keepGoing) {
//
//                Thread.sleep(1)
//            }
//        }
//        masker.start()
//        val result = try {
//            readln()
//        } finally {
//            keepGoing = false
//            masker.join()
//        }
//        result
        val result = readln()
        print("\b".repeat(result.length + 1))
        println()
        result
    }
}

public class EncryptedFileSecretSource(
    private val file: File,
    override val name: String = file.toString(),
    private val passwordFetcher: PasswordFetcher = PasswordFetcher(),
) : PopulatableSecretSource {

    public constructor(name: String, passwordFetcher: PasswordFetcher = PasswordFetcher()) : this(
        File(System.getProperty("user.home"), ".lightningserver/$name.json.enc"),
        "~/.lightningserver/$name.json.enc",
        passwordFetcher
    )

    private val json = Json
    private val provider = CryptographyProvider.Default.get(AES.CBC)
    private var encryptionKey: AES.CBC.Key? = null
    private val hmac = CryptographyProvider.Default.get(SHA256)
    private var map: HashMap<String, String>? = null

    private fun getMap(): MutableMap<String, String> {
        return map ?: run {
            if (file.exists()) {
                while (true) {
                    val password = passwordFetcher.read("Enter your secrets password for $file:") { password ->
                        // Ensure the password is correct
                        val secretBytes = hmac.hasher().hashBlocking(password.toByteArray(Charsets.UTF_8))
                        val result = provider.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, secretBytes)
                        result.cipher().decryptBlocking(file.readBytes()).toString(Charsets.UTF_8)
                    }
                    val secretBytes = hmac.hasher().hashBlocking(password.toByteArray(Charsets.UTF_8))
                    val result = provider.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, secretBytes)
                    result.cipher().decryptBlocking(file.readBytes()).toString(Charsets.UTF_8).let {
                        json.decodeFromString<HashMap<String, String>>(it)
                    }.also { map = it; encryptionKey = result }
                    break
                }
                map!!
            } else {
                val password = passwordFetcher.read("Pick your secrets password for $file:") { password -> }
                val secretBytes = hmac.hasher().hashBlocking(password.toByteArray(Charsets.UTF_8))
                val result = provider.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, secretBytes)
                this.encryptionKey = result
                val newMap = HashMap<String, String>()
                map = newMap
                file.parentFile.mkdirs()
                file.createNewFile()
                file.writeBytes(
                    result.cipher().encryptBlocking(json.encodeToString(map).toByteArray(Charsets.UTF_8))
                )
                newMap
            }
        }
    }

    override fun <T> getOrNull(need: TerraformNeed<T>): T? {
        return getMap()[need.name]?.let { json.decodeFromString(need.serializer, it) }
    }

    override fun <T> set(need: TerraformNeed<T>, value: T) {
        getMap()[need.name] = json.encodeToString(need.serializer, value)
        file.writeBytes(
            encryptionKey!!.cipher().encryptBlocking(Json.encodeToString(map).toByteArray(Charsets.UTF_8))
        )
    }
}