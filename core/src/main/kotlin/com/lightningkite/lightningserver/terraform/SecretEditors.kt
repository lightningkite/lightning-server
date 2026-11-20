package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.data.StringArrayFormat
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import javax.swing.JOptionPane

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

    @Suppress("UNCHECKED_CAST")
    fun TerraformNeed<*>.currentString(): String? = try {
        getBoxed(this)?.let { (this as TerraformNeed<Any?>).serializer.emit(it.value) }
    } catch (_: Exception) {
        null
    }

    while (true) {
        val optionLabels = (variables.map { v ->
            if (v.currentString() != null) "${v.name} (set)" else v.name
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
                val currentStr = selectedNeed.currentString()

                val message = buildString {
                    append("${selectedNeed.name}: ${selectedNeed.instructions}\n")
                    if (currentStr != null) append("Current: $currentStr\n")
                    selectedNeed.blankHint().takeIf { it.isNotEmpty() }?.let { append("$it\n") }
                }

                val input = JOptionPane.showInputDialog(null, message, currentStr ?: "") ?: break
                try {
                    @Suppress("UNCHECKED_CAST")
                    val need = selectedNeed as TerraformNeed<Any?>
                    val typed = if (input.isBlank()) need.blankValue() else need.serializer.parse(input)
                    pop.set(need, typed)
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
            val currentStr = selectedNeed.currentString() ?: "<unset>"
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
        if (read) println(getBoxed(sel)?.let { "The current value is '${it.value}'." } ?: "It is not set.")
        else prompt(sel)
    }
}

/**
 * The value meant by an empty entry for this need.
 *
 * For a nullable need, `default == null` *is* the default - [TerraformNeed.default] has no way to
 * express "no default" for such a need - so blank means null there rather than being an error.
 *
 * @throws IllegalArgumentException if the need has no default and null is not a legal value.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> TerraformNeed<T>.blankValue(): T = when {
    default != null -> default!!
    serializer.descriptor.isNullable -> null as T
    else -> throw IllegalArgumentException("'$name' has no default; please enter a value.")
}

/** Sentence describing what an empty entry does, or "" when an entry is required. */
internal fun <T> TerraformNeed<T>.blankHint(): String = when {
    default != null -> "Leave blank to default to ${serializer.emit(default!!)}."
    serializer.descriptor.isNullable -> "Leave blank for null."
    else -> ""
}

/**
 * Prints this need's instructions and reads one value for it from the terminal, retrying until the
 * input parses.
 *
 * @param destination Describes where the value is headed, e.g. "to be stored in AWS Secrets Manager".
 */
internal fun <T> TerraformNeed<T>.readFromTerminal(destination: String): T {
    println("$name: $instructions")
    blankHint().takeIf { it.isNotEmpty() }?.let { println(it) }
    println("Enter value for '$name' $destination: ")
    return readInput { typed -> if (typed.isBlank()) blankValue() else serializer.parse(typed) }
}

/**
 * Parses a string into the type represented by this serializer.
 * Handles both primitive types (using StringArrayFormat) and complex types (using JSON).
 *
 * @throws IllegalArgumentException if the string doesn't match the expected format
 */
internal fun <T> KSerializer<T>.parse(string: String): T = try {
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
internal fun <T> KSerializer<T>.emit(value: T): String =
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
        val input = readln()
        try {
            return process(input)
        } catch (e: IllegalArgumentException) {
            println("Try again: " + e.message)
        }
    }
}
