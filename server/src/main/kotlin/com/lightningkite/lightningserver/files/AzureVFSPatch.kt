package com.lightningkite.lightningserver.files

import com.azure.storage.blob.BlobClient
import com.azure.storage.blob.BlobContainerAsyncClient
import com.azure.storage.blob.BlobContainerClient
import com.azure.storage.blob.BlobContainerClientBuilder
import com.azure.storage.blob.sas.BlobSasPermission
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues
import com.azure.storage.common.StorageSharedKeyCredential
import com.azure.storage.common.sas.SasProtocol
import com.dalet.vfs2.provider.azure.*
import org.apache.commons.vfs2.*
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder
import org.apache.commons.vfs2.provider.FileReplicator
import org.apache.commons.vfs2.provider.TemporaryFileStore
import org.apache.commons.vfs2.provider.VfsComponentContext
import org.apache.commons.vfs2.util.UserAuthenticatorUtils
import java.io.File
import java.net.URI
import java.time.OffsetDateTime
import java.util.*

private val blobContainerClients = HashMap<String, BlobContainerClient>()

private fun AzFileObject.hackBlobClient(): BlobClient {
    val rootURI = this.fileSystem.rootURI
    val blobContainerClient =  blobContainerClients.getOrPut(rootURI) {
        val azRootName = AzFileNameParser.getInstance().parseUri(object : VfsComponentContext {
            override fun resolveFile(
                baseFile: FileObject?,
                name: String?,
                fileSystemOptions: FileSystemOptions?
            ): FileObject = throw UnsupportedOperationException()

            override fun resolveFile(name: String?, fileSystemOptions: FileSystemOptions?): FileObject =
                throw UnsupportedOperationException()

            override fun parseURI(uri: String?): FileName = throw UnsupportedOperationException()
            override fun getReplicator(): FileReplicator = throw UnsupportedOperationException()
            override fun getTemporaryFileStore(): TemporaryFileStore = throw UnsupportedOperationException()
            override fun toFileObject(file: File?): FileObject = throw UnsupportedOperationException()
            override fun getFileSystemManager(): FileSystemManager = throw UnsupportedOperationException()
        }, null, rootURI) as AzFileName

        val resolvedFileSystemOptions = AzFileProvider.getDefaultFileSystemOptions()
        val ua = DefaultFileSystemConfigBuilder.getInstance().getUserAuthenticator(resolvedFileSystemOptions)

        var authData: UserAuthenticationData? = null

        try {
            authData = ua.requestAuthentication(AzFileProvider.AUTHENTICATOR_TYPES)
            val accountName = UserAuthenticatorUtils.toString(
                UserAuthenticatorUtils.getData(
                    authData,
                    UserAuthenticationData.USERNAME, null
                )
            )
            val accountKey = UserAuthenticatorUtils.toString(
                UserAuthenticatorUtils.getData(
                    authData,
                    UserAuthenticationData.PASSWORD, null
                )
            )
            val storageCreds = StorageSharedKeyCredential(accountName, accountKey)
            val endPoint = String.format(
                Locale.ROOT, "https://%s.blob.core.windows.net/%s", azRootName.account,
                azRootName.container
            )
            BlobContainerClientBuilder()
                .endpoint(endPoint)
                .credential(storageCreds)
                .buildClient()
        } finally {
            UserAuthenticatorUtils.cleanup(authData)
        }
    }
    return blobContainerClient.getBlobClient(name.path.removePrefix("/"))
}

fun AzFileObject.uploadUrl(seconds: Int): String {
    val offsetDateTime = OffsetDateTime.now().plusSeconds(seconds.toLong())
    val sasPermission = BlobSasPermission.parse("w")

    val signatureValues = BlobServiceSasSignatureValues(offsetDateTime, sasPermission)

    signatureValues.startTime = OffsetDateTime.now().minusMinutes(10)
    signatureValues.protocol = SasProtocol.HTTPS_ONLY

    // Sign the url for this object
    val blobClient = hackBlobClient()
    return blobClient.blobUrl + "?" + blobClient.generateSas(signatureValues)
}