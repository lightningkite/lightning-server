package com.lightningkite.lightningserver.files

import com.azure.storage.blob.BlobContainerClientBuilder
import com.azure.storage.common.StorageSharedKeyCredential
import java.io.File
import java.time.Duration
import java.util.*

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
                val withoutScheme = it.storageUrl.substringAfter("://")
                val key = withoutScheme.substringBefore('@')
                val accountAndContainer = withoutScheme.substringAfter('@')
                val account = accountAndContainer.substringBefore('/')
                val container = accountAndContainer.substringAfter('/')
                AzureFileSystem(
                    account = account,
                    container = container,
                    key = key,
                    signedUrlExpirationSeconds = it.signedUrlExpirationSeconds ?: Duration.ofDays(1).toSeconds().toInt()
                )
            }
        }
    }

    override val root: FileObject = AzureFile(this, File(""))
}

