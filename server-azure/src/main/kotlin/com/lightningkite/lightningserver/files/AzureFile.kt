package com.lightningkite.lightningserver.files

import com.azure.core.util.BinaryData
import com.azure.storage.blob.models.ListBlobsOptions
import com.azure.storage.blob.sas.BlobSasPermission
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues
import com.azure.storage.common.sas.SasProtocol
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.OffsetDateTime

data class AzureFile(val system: AzureFileSystem, val path: File) : FileObject {
    val client by lazy { system.blobContainerClient.getBlobClient(path.name) }
    override fun resolve(path: String): FileObject = AzureFile(system, this.path.resolve(path))

    override val parent: FileObject?
        get() = path.parentFile?.let { AzureFile(system, path) } ?: if (path.path.isNotEmpty()) system.root else null

    override suspend fun info(): FileInfo? = withContext(Dispatchers.IO) {
        try {
            client.properties.let {
                FileInfo(
                    type = ContentType(it.contentType),
                    size = it.blobSize,
                    lastModified = it.lastModified.toInstant()
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
        try {
            system.blobContainerClient.listBlobs(
                ListBlobsOptions().setPrefix(path.path),
                Duration.ofSeconds(5)
            )
                .filter { !it.name.substringAfter(path.toString()).contains('/') }
                .map { AzureFile(system, File(it.name)) }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun write(content: HttpContent) {
        withContext(Dispatchers.IO) {
            content.length?.let {
                client.upload(content.stream(), it, true)
            } ?: run {
                client.upload(BinaryData.fromStream(content.stream()), true)
            }
        }
    }

    override suspend fun read(): InputStream {
        return withContext(Dispatchers.IO) {
            client.openInputStream()
        }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            client.deleteIfExists()
        }
    }

    override fun checkSignature(queryParams: String): Boolean {
        return super.checkSignature(queryParams)
    }

    override val url: String
        get() = client.blobUrl

    override val signedUrl: String get() {
        val offsetDateTime = OffsetDateTime.now().plusSeconds(system.signedUrlExpirationSeconds.toLong())
        val sasPermission = BlobSasPermission().setReadPermission(true)

        val signatureValues = BlobServiceSasSignatureValues(offsetDateTime, sasPermission)

        signatureValues.startTime = OffsetDateTime.now().minusMinutes(10)
        signatureValues.protocol = SasProtocol.HTTPS_ONLY

        // Sign the url for this object
        return client.blobUrl + "?" + client.generateSas(signatureValues)
    }

    override fun uploadUrl(timeoutMilliseconds: Long): String {
        val offsetDateTime = OffsetDateTime.now().plus(Duration.ofMillis(timeoutMilliseconds))
        val sasPermission = BlobSasPermission().setWritePermission(true).setCreatePermission(true).setAddPermission(true)

        val signatureValues = BlobServiceSasSignatureValues(offsetDateTime, sasPermission)

        signatureValues.startTime = OffsetDateTime.now().minusMinutes(10)
        signatureValues.protocol = SasProtocol.HTTPS_ONLY

        // Sign the url for this object
        return client.blobUrl + "?" + client.generateSas(signatureValues)
    }
    override fun toString(): String = url
}