package com.lightningkite.lightningserver.guide.samples

// region running-imports
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.kfile.KFile
import io.ktor.server.netty.Netty
// endregion running-imports

// region serve
fun serve() {
    val built = HelloServer.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
// endregion serve

// region predeploy
fun predeploy() {
    KtorEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        runPreDeploy()
    }
}

fun dev() {
    KtorEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        settings.ready()
        runPreDeployTasksBlocking()
        start(Netty)
    }
}
// endregion predeploy
