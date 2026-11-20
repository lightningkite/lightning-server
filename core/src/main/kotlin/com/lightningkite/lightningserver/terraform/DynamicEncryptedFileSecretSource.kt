package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed
import java.io.File
import java.util.Properties

/**
 * An [EncryptedFileSecretSource] whose file location is asked for at runtime instead of hard-coded, so each
 * developer who deploys can keep the secrets file wherever they like.
 *
 * When [cacheLocation] is `true`, the location entered is remembered in `local.secretFiles.properties` in the
 * working directory, keyed by [name], so one file can hold the locations for several deployments. That file
 * must not be checked into version control.
 *
 * @param name Display name for this secret source, and the key its location is cached under.
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
