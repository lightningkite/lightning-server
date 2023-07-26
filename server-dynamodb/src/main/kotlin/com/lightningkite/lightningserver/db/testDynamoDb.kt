package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.logger
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.io.File
import java.net.URI
import java.net.URL
import java.util.zip.ZipInputStream

private val url = URL("https://s3.us-west-2.amazonaws.com/dynamodb-local/dynamodb_local_latest.zip")
private val localFolder = File(System.getProperty("user.home")).resolve(".dynamodblocal")

private var existingDynamo: DynamoDbAsyncClient? = null
fun embeddedDynamo(port: Int = 7999): DynamoDbAsyncClient {
    existingDynamo?.let { return it }
    if(!localFolder.exists()) {
        localFolder.mkdirs()
        logger.info("Downloading local DynamoDB...")
        ZipInputStream(url.openStream()).use {
            while(true) {
                val next = it.nextEntry ?: break
                val dest = localFolder.resolve(next.name)
                if(next.isDirectory) {
                    it.closeEntry()
                    continue
                }
                dest.parentFile!!.mkdirs()
                dest.outputStream().use { out ->
                    it.copyTo(out)
                }
                it.closeEntry()
            }
        }
        logger.info("Download complete.")
    }
    val server = ProcessBuilder()
        .directory(localFolder)
        .inheritIO()
        .command("java", "-Djava.library.path=./DynamoDBLocal_lib", "-jar", "DynamoDBLocal.jar", "-inMemory", "-port", port.toString())
        .start()
    Thread.sleep(1000L)
    val shutdownHook = Thread {
        existingDynamo = null
        try {
            server.destroy()
        } catch (e: Exception) {
            /*squish*/
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    val raw = DynamoDbAsyncClient.builder()
        .region(Region.US_WEST_2)
        .endpointOverride(URI.create("http://localhost:$port"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummykey", "dummysecret")))
        .build()
    val newDynamo = object : DynamoDbAsyncClientDelegate(raw) {
        override fun close() {
            super.close()
            existingDynamo = null
            try {
                server.destroy()
            } catch (e: Exception) {
                /*squish*/
            }
        }
    }
    existingDynamo = newDynamo
    return newDynamo
}