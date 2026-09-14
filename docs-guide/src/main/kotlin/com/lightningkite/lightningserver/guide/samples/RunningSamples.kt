package com.lightningkite.lightningserver.guide.samples

// region running-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import kotlinx.coroutines.*
// endregion running-imports

// A minimal server definition used by the running-chapter build sample.
// Kept private to this file so it does not clash with HelloServer in FirstEndpointSamples.
private object RunningExampleServer : ServerBuilder() {

    // GET / — simple root response
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello from RunningExampleServer!")
    }
}

// region build-server
// ServerBuilder.build() seals all registries, caches calculations, and returns
// a ServerDefinition — a pure description of your server with no external connections.
// Passing the result to an engine is what actually binds a port and starts accepting requests.
fun buildServer() {
    val built = RunningExampleServer.build()
    // `built` is a ServerDefinition: all endpoint registries are sealed and ready.
    // Hand it to an engine to run (see the illustrative main() example in the chapter).
    println("Server definition created with ${built.endpoints.entries.count()} endpoint entries")
}
// endregion build-server
