package kspcommon

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import com.lightningkite.services.database.processor.MyProvider
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.copyTo
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.walk

/**
 * Runs the Service Abstractions database-processor once over a module's COMMON sources,
 * writing into a directory `plugin.yaml` declares as common generated sources.
 *
 * The Toolchain's own KSP integration runs the processor once per platform and attaches each
 * result to that platform's fragment. The generated model code contains `@get:JvmName`, which
 * off-JVM is an `@OptionalExpectation` annotation and is only legal in common sources, so the
 * built-in path fails to compile on JS and every Apple target. Driving KSP directly through the
 * KSP2 standalone API and declaring the output as common restores Gradle's behaviour.
 *
 * KSPJvmConfig is deliberate: under KSPCommonConfig the processor cannot resolve library types
 * (annotations come back null and property types UNRESOLVED) and silently generates wrong output.
 */
@TaskAction
@OptIn(ExperimentalPathApi::class)
fun runKspOverCommon(
    @Input commonSrcDir: Path,
    @Input classpath: Classpath,
    @Output outputDir: Path,
) {
    // The database-processor is not path-agnostic: it locates an ancestor directory literally
    // named `build`, derives a Main/Test "flavor" from the path segment two past a `ksp`
    // segment, and then writes its real output itself to
    // <projectFolder>/build/generated/ksp/common/common<flavor>/kotlin, ignoring the
    // configured kotlinOutputDir. So the KSP scratch dirs below are deliberately shaped like
    // Gradle's, and plugin.yaml points `generated.sources` at the path the processor picks.
    val moduleDir = commonSrcDir.parent
    val kspBase = moduleDir / "build" / "ksp" / "common" / "commonMain"
    outputDir.deleteRecursively()
    outputDir.createDirectories()

    val config = KSPJvmConfig.Builder().apply {
        javaSourceRoots = emptyList()
        javaOutputDir = (kspBase / "java").createDirectories().toFile()
        jvmTarget = "17"
        moduleName = "commonKsp"
        sourceRoots = listOf(commonSrcDir.toFile())
        commonSourceRoots = listOf(commonSrcDir.toFile())
        libraries = classpath.resolvedFiles.map { it.toFile() }
        projectBaseDir = moduleDir.toFile()
        outputBaseDir = kspBase.toFile()
        cachesDir = (kspBase / "caches").createDirectories().toFile()
        kotlinOutputDir = (kspBase / "kotlin").createDirectories().toFile()
        classOutputDir = (kspBase / "classes").createDirectories().toFile()
        resourceOutputDir = (kspBase / "resources").createDirectories().toFile()
        incremental = false
        languageVersion = "2.2"
        apiVersion = "2.2"
    }.build()

    val logger = object : KSPLogger {
        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) {}
        override fun warn(message: String, symbol: KSNode?): Unit = println("[ksp] w: $message")
        override fun error(message: String, symbol: KSNode?): Unit = println("[ksp] e: $message")
        override fun exception(e: Throwable) { e.printStackTrace() }
    }

    val exit = KotlinSymbolProcessing(config, listOf(MyProvider()), logger).execute()
    if (exit != KotlinSymbolProcessing.ExitCode.OK) error("KSP failed over common sources: $exit")

    // The processor chose its own destination (see above); copy what it produced into the
    // directory this task actually declares as its @Output, which is what plugin.yaml
    // registers as a common source directory.
    val processorOutput = moduleDir / "build" / "generated" / "ksp" / "common" / "commonMain" / "kotlin"
    var copied = 0
    if (processorOutput.exists()) {
        processorOutput.walk().filter { it.extension == "kt" }.forEach { src ->
            val dest = outputDir.resolve(processorOutput.relativize(src).toString())
            dest.parent.createDirectories()
            src.copyTo(dest, overwrite = true)
            copied++
        }
    }
    println("[ksp-common] ${moduleDir.fileName}: $exit, $copied generated file(s)")
}
