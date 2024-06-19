package com.lightningkite.lightningserver.files

import com.azure.storage.blob.BlobContainerClientBuilder
import com.azure.storage.common.StorageSharedKeyCredential
import java.io.File
import kotlin.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.days

class AzureFileSystem(
    val account: String,
    val container: String,
    val key: String,
    val signedUrlExpirationSeconds: Int
) : FileSystem {
    val blobContainerClient = run {
        val storageCreds = StorageSharedKeyCredential(account, key)
        val endPoint = String.format(
            Locale.ROOT, "https://%s.blob.core.windows.net/%s", account,
            container
        )
        BlobContainerClientBuilder()
            .endpoint(endPoint)
            .credential(storageCreds)
            .buildClient()
    }

    companion object {
        init {
            FilesSettings.register("azbs") {
                val withoutScheme = it.url.substringAfter("://")
                val key = withoutScheme.substringBefore('@')
                val accountAndContainer = withoutScheme.substringAfter('@')
                val account = accountAndContainer.substringBefore('/')
                val container = accountAndContainer.substringAfter('/')
                AzureFileSystem(
                    account = account,
                    container = container,
                    key = key,
                    signedUrlExpirationSeconds = (it.signedUrlExpiration ?: 1.days).inWholeSeconds.toInt()
                )
            }
        }
    }

    override val root: FileObject = AzureFile(this, File(""))

    init {
        FileSystem.register(this)
    }
}

