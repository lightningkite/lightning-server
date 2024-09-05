package com.lightningkite.lightningserver.filescanner

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.files.FileObject
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.net.URLConnection
import java.util.*
import com.lightningkite.UUID


interface FileScanner {
    enum class Requires { Nothing, FirstSixteenBytes, Whole }
    fun requires(claimedType: ContentType): Requires
    fun scan(claimedType: ContentType, data: InputStream)
//    fun scan(item: FileObject) = runBlocking { scan(item.get()!!) }
//    fun scan(item: HttpContent)
}
suspend fun FileScanner.scan(item: HttpContent) = scan(item.type, item.stream())
suspend fun FileScanner.copyAndScan(source: FileObject, destination: FileObject) {
    try {
        source.copyTo(destination)
        scan(source.get()!!)
    } catch(e: Exception) {
        destination.delete()
        throw e
    }
}
suspend fun List<FileScanner>.scan(item: HttpContent) {
    val all = drop(1).map {
        val t = Thread { runBlocking { it.scan(item.type, item.stream()) } }
        t.start()
        t
    }
    firstOrNull()?.scan(item.type, item.stream())
    all.forEach { it.join() }
}
suspend fun List<FileScanner>.copyAndScan(source: FileObject, destination: FileObject) {
    try {
        source.copyTo(destination)
        scan(source.get()!!)
    } catch(e: Exception) {
        destination.delete()
        throw e
    }
}

object CheckMimeFileScanner : FileScanner {
    override fun requires(claimedType: ContentType): FileScanner.Requires = FileScanner.Requires.FirstSixteenBytes
    override fun scan(claimedType: ContentType, data: InputStream) {
        val bytes = ByteArray(16)
        data.use {
            data.read(bytes)
        }
        val c1 = bytes[0].toUByte().toInt()
        val c2 = bytes[1].toUByte().toInt()
        val c3 = bytes[2].toUByte().toInt()
        val c4 = bytes[3].toUByte().toInt()
        val c5 = bytes[4].toUByte().toInt()
        val c6 = bytes[5].toUByte().toInt()
        val c7 = bytes[6].toUByte().toInt()
        val c8 = bytes[7].toUByte().toInt()
        val c9 = bytes[8].toUByte().toInt()
        val c10 = bytes[9].toUByte().toInt()
        val c11 = bytes[10].toUByte().toInt()
        when(claimedType) {
            ContentType.Image.JPEG -> {
                if (c1 == 0xFF && c2 == 0xD8 && c3 == 0xFF) {
                    if (c4 == 0xE0 || c4 == 0xEE) {
                        return
                    }

                    /**
                     * File format used by digital cameras to store images.
                     * Exif Format can be read by any application supporting
                     * JPEG. Exif Spec can be found at:
                     * http://www.pima.net/standards/it10/PIMA15740/Exif_2-1.PDF
                     */
                    if ((c4 == 0xE1) &&
                        (c7 == 'E'.code && c8 == 'x'.code && c9 == 'i'.code && c10 == 'f'.code && c11 == 0)
                    ) {
                        return
                    }
                }
                throw BadRequestException("Mime type mismatch; doesn't fit the JPEG format ${c1.toUByte().toString(16)} ${c2.toUByte().toString(16)} ${c3.toUByte().toString(16)} ${c4.toUByte().toString(16)}")
            }
            ContentType.Image.GIF -> {
                if(c1 == 'G'.code && c2 == 'I'.code && c3 == 'F'.code && c4 == '8'.code) return
                throw BadRequestException("Mime type mismatch; doesn't fit the GIF format")
            }
            ContentType.Image.Tiff -> {

                if ((c1 == 0x49 && c2 == 0x49 && c3 == 0x2a && c4 == 0x00)
                    || (c1 == 0x4d && c2 == 0x4d && c3 == 0x00 && c4 == 0x2a)) {
                    return
                }
                throw BadRequestException("Mime type mismatch; doesn't fit the TIFF format")
            }
            ContentType.Image.PNG -> {
                if (c1 == 137 && c2 == 80 && c3 == 78 &&
                    c4 == 71 && c5 == 13 && c6 == 10 &&
                    c7 == 26 && c8 == 10) {
                    return
                }
                throw BadRequestException("Mime type mismatch; doesn't fit the PNG format")
            }
            in ContentType.xmlTypes -> {
                if(bytes.toString(Charsets.UTF_8).trimStart().firstOrNull() == '<') return
                if(bytes.toString(Charsets.UTF_16BE).trimStart().firstOrNull() == '<') return
                if(bytes.toString(Charsets.UTF_16LE).trimStart().firstOrNull() == '<') return
                if(bytes.toString(Charsets.UTF_32LE).trimStart().firstOrNull() == '<') return
                if(bytes.toString(Charsets.UTF_32BE).trimStart().firstOrNull() == '<') return
                throw BadRequestException("Mime type mismatch; doesn't fit the XML format")
            }
        }
    }
}

/**
 * Settings that define a file scanner for security and other processing.
 */
@Serializable
data class FileScannerSettings(
    val urls: List<String> = listOf()
) : () -> List<FileScanner> {
    companion object : Pluggable<String, FileScanner>() {
        init {
            register("mime") { CheckMimeFileScanner }
        }
    }

    override fun invoke(): List<FileScanner> = urls.map { parse(it.substringBefore("://"), it) }
}

