package com.lightningkite.ktorbatteries.files

import com.amazonaws.AmazonServiceException
import com.amazonaws.HttpMethod
import com.github.vfss3.S3FileObject
import com.github.vfss3.S3FileSystem
import org.apache.commons.vfs2.FileSystemException
import java.util.*

fun S3FileObject.uploadUrl(seconds: Int): String {
    return object: S3FileObject(this.name, this.fileSystem as S3FileSystem) {
        fun uploadUrl(seconds: Int): String {
            val cal = Calendar.getInstance()

            cal.add(Calendar.SECOND, seconds)

            return try {
                service.generatePresignedUrl(
                    bucketName,
                    name.s3Key.orElseThrow {
                        FileSystemException(
                            "Not able get presigned url for a bucket"
                        )
                    },
                    cal.time,
                    HttpMethod.PUT
                ).toString()
            } catch (e: AmazonServiceException) {
                throw FileSystemException(e)
            }
        }
    }.uploadUrl(seconds)
}