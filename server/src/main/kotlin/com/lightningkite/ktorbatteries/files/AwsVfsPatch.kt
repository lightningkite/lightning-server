package com.lightningkite.ktorbatteries.files

import com.amazonaws.AmazonServiceException
import com.amazonaws.HttpMethod
import com.github.vfss3.S3FileObject
import com.github.vfss3.S3FileSystem
import org.apache.commons.vfs2.FileName
import org.apache.commons.vfs2.FileSystemException
import org.apache.commons.vfs2.FileType
import java.util.*

/**
 * A patch that allows generating a pre-signed upload url to s3
 */
fun S3FileObject.uploadUrl(seconds: Int): String {
    return object: S3FileObject(this.name, this.fileSystem as S3FileSystem) {
        fun uploadUrl(seconds: Int): String {
            val cal = Calendar.getInstance()

            cal.add(Calendar.SECOND, seconds)

            return try {
                service.generatePresignedUrl(
                    bucketName,
                    name.run {
                        if (getPathDecoded() == FileName.ROOT_PATH) {
                            return@run Optional.empty<String>()
                        }

                        val path: StringBuilder = StringBuilder(getPathDecoded())

                        if (path.indexOf(FileName.SEPARATOR) == 0 && path.length > 1) {
                            path.deleteCharAt(0)
                        }

                        if (type == FileType.FOLDER) {
                            path.append(FileName.SEPARATOR_CHAR)
                        }

                        return@run Optional.of(path.toString())
                    }.orElseThrow {
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

/**
 * A patch to prevent generating a signed url from making external calls which will be slow.
 */
fun S3FileObject.unstupidSignUrl(seconds: Int): String {
    return object : S3FileObject(this.name, this.fileSystem as S3FileSystem) {
        fun unstupidSignUrl(expireInSeconds: Int): String {
            val cal = Calendar.getInstance()

            cal.add(Calendar.SECOND, expireInSeconds)

            return try {
                service.generatePresignedUrl(
                    bucketName,
                    name.run {
                        if (getPathDecoded() == FileName.ROOT_PATH) {
                            return@run Optional.empty<String>()
                        }

                        val path: StringBuilder = StringBuilder(getPathDecoded())

                        if (path.indexOf(FileName.SEPARATOR) == 0 && path.length > 1) {
                            path.deleteCharAt(0)
                        }

                        if (type == FileType.FOLDER) {
                            path.append(FileName.SEPARATOR_CHAR)
                        }

                        return@run Optional.of(path.toString())
                    }.orElseThrow {
                        FileSystemException(
                            "Not able get presigned url for a bucket"
                        )
                    },
                    cal.time
                ).toString()
            } catch (e: AmazonServiceException) {
                throw FileSystemException(e)
            }
        }
    }.unstupidSignUrl(seconds)
}