package com.lightningkite

import kotlin.jvm.JvmInline

@JvmInline
value class MimeType(val string: String) {
    // TODO: No escaping?  Maybe this should escape.
    constructor(type: String, subtype: String):this("$type/$subtype")
    constructor(type: String, subtype: String, parameters: List<Pair<String, String>>):this("$type/$subtype" + parameters.joinToString("") { ";${it.first}=${it.second}" })
    val withoutParameters: MimeType get() = MimeType(string.substringBefore(';'))
    val type: String get() = string.substringBefore('/')
    val subtype: String get() = string.substringAfter('/').substringBefore(';')
    val normalize: MimeType get() = MimeType(type, subtype, parameters)
    val parameters: List<Pair<String, String>> get() = string.substringAfter(';', "").split(';').filter { it.isNotBlank() }.map { it.substringBefore('=') to it.substringAfter('=', "true") }
    override fun toString(): String = string
    val extension: String? get() = toFileExtension[this]
    val isText: Boolean
        get() = when (this) {
            Application.Json -> true
            Application.Xml -> true
            Application.JavaScript -> true
            Application.ProblemJson -> true
            Application.ProblemXml -> true
            else -> this.type == "text"
        }
    companion object {
        private val fromFileExtension by lazy {
            mapOf(
                "bin" to Application.OctetStream,
                "cbor" to Application.Cbor,
                "json" to Application.Json,
                "bson" to Application.Bson,
                "js" to Application.JavaScript,
                "xml" to Application.Xml,
                "zip" to Application.Zip,
                "wav" to Audio.WAV,
                "mp3" to Audio.MP3,
                "mpg" to Video.MPEG,
                "mpeg" to Video.MPEG,
                "mp4" to Video.MP4,
                "ogg" to Video.OGG,
                "mov" to Video.QuickTime,
                "txt" to Text.Plain,
                "css" to Text.CSS,
                "csv" to Text.CSV,
                "htm" to Text.Html,
                "html" to Text.Html,
                "js" to Text.JavaScript,
                "xml" to Text.Xml,
                "gif" to Image.GIF,
                "jpg" to Image.JPEG,
                "jpeg" to Image.JPEG,
                "png" to Image.PNG,
                "svg" to Image.SVG,
                "webp" to Image.WebP,
                "jp2" to Image.JPEG2000,
                "pdf" to Application.Pdf,
                "xlsx" to Application.Xlsx,
                "docx" to Application.Docx,
                "pptx" to Application.Pptx,
            )
        }
        private val toFileExtension by lazy { fromFileExtension.entries.associate { it.value to it.key } }
        fun fromExtension(extension: String) = fromFileExtension[extension.lowercase()] ?: Application.OctetStream
    }
    /**
     * Provides a list of standard subtypes of an `application` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Application {
        /**
         * Represents a pattern `application / *` to match any application content type.
         */
        public val Any: MimeType get() = MimeType("application/*")
        public val Atom: MimeType get() = MimeType("application/atom+xml")
        public val Cbor: MimeType get() = MimeType("application/cbor")
        public val Json: MimeType get() = MimeType("application/json")
        public val Bson: MimeType get() = MimeType("application/bson")
        public val HalJson: MimeType get() = MimeType("application/hal+json")
        public val JavaScript: MimeType get() = MimeType("application/javascript")
        public val OctetStream: MimeType get() = MimeType("application/octet-stream")
        public val FontWoff: MimeType get() = MimeType("application/font-woff")
        public val Rss: MimeType get() = MimeType("application/rss+xml")
        public val Xml: MimeType get() = MimeType("application/xml")
        public val Xml_Dtd: MimeType get() = MimeType("application/xml-dtd")
        public val Zip: MimeType get() = MimeType("application/zip")
        public val GZip: MimeType get() = MimeType("application/gzip")

        public val FormUrlEncoded: MimeType get() =
            MimeType("application", "x-www-form-urlencoded")

        public val Pdf: MimeType get() = MimeType("application/pdf")
        public val Xlsx: MimeType get() = MimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        public val Docx: MimeType get() = MimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        public val Pptx: MimeType get() = MimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation")
        public val ProtoBuf: MimeType get() = MimeType("application/protobuf")
        public val Wasm: MimeType get() = MimeType("application/wasm")
        public val ProblemJson: MimeType get() = MimeType("application/problem+json")
        public val ProblemXml: MimeType get() = MimeType("application/problem+xml")
    }

    /**
     * Provides a list of standard subtypes of an `audio` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Audio {
        public val Any: MimeType get() = MimeType("audio/*")
        public val WAV: MimeType get() = MimeType("audio/wav")
        public val MP3: MimeType get() = MimeType("audio/mp3")
        public val MP4: MimeType get() = MimeType("audio/mp4")
        public val MPEG: MimeType get() = MimeType("audio/mpeg")
        public val OGG: MimeType get() = MimeType("audio/ogg")
    }

    /**
     * Provides a list of standard subtypes of an `image` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Image {
        public val Any: MimeType get() = MimeType("image/*")
        public val GIF: MimeType get() = MimeType("image/gif")
        public val JPEG2000: MimeType get() = MimeType("image/jp2")
        public val JPEG: MimeType get() = MimeType("image/jpeg")
        public val PNG: MimeType get() = MimeType("image/png")
        public val SVG: MimeType get() = MimeType("image/svg+xml")
        public val WebP: MimeType get() = MimeType("image/webp")
        public val XIcon: MimeType get() = MimeType("image/x-icon")
    }

    /**
     * Provides a list of standard subtypes of a `message` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Message {
        public val Any: MimeType get() = MimeType("message/*")
        public val Http: MimeType get() = MimeType("message/http")
    }

    /**
     * Provides a list of standard subtypes of a `multipart` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object MultiPart {
        public val Any: MimeType get() = MimeType("multipart/*")
        public val Mixed: MimeType get() = MimeType("multipart/mixed")
        public val Alternative: MimeType get() = MimeType("multipart/alternative")
        public val Related: MimeType get() = MimeType("multipart/related")
        public val FormData: MimeType get() = MimeType("multipart/form-data")
        public val Signed: MimeType get() = MimeType("multipart/signed")
        public val Encrypted: MimeType get() = MimeType("multipart/encrypted")
        public val ByteRanges: MimeType get() = MimeType("multipart/byteranges")
    }

    /**
     * Provides a list of standard subtypes of a `text` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Text {
        public val Any: MimeType get() = MimeType("text/*")
        public val Plain: MimeType get() = MimeType("text/plain;charset=utf-8")
        public val CSS: MimeType get() = MimeType("text/css;charset=utf-8")
        public val CSV: MimeType get() = MimeType("text/csv;charset=utf-8")
        public val Html: MimeType get() = MimeType("text/html;charset=utf-8")
        public val JavaScript: MimeType get() = MimeType("text/javascript;charset=utf-8")
        public val VCard: MimeType get() = MimeType("text/vcard;charset=utf-8")
        public val Xml: MimeType get() = MimeType("text/xml;charset=utf-8")
        public val EventStream: MimeType get() = MimeType("text/event-stream;charset=utf-8")
        public val UriList: MimeType get() = MimeType("text/uri-list;charset=utf-8")
    }

    /**
     * Provides a list of standard subtypes of a `video` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Video {
        public val Any: MimeType get() = MimeType("video/*")
        public val MPEG: MimeType get() = MimeType("video/mpeg")
        public val MP4: MimeType get() = MimeType("video/mp4")
        public val OGG: MimeType get() = MimeType("video/ogg")
        public val QuickTime: MimeType get() = MimeType("video/quicktime")
    }
}


//
//    companion object {
//        private val fromFileExtension by lazy {
//            mapOf(
//                "bin" to Application.OctetStream,
//                "cbor" to Application.Cbor,
//                "json" to Application.Json,
//                "bson" to Application.Bson,
//                "js" to Application.JavaScript,
//                "xml" to Application.Xml,
//                "zip" to Application.Zip,
//                "wav" to Audio.WAV,
//                "mp3" to Audio.MP3,
//                "mpg" to Video.MPEG,
//                "mpeg" to Video.MPEG,
//                "mp4" to Video.MP4,
//                "ogg" to Video.OGG,
//                "mov" to Video.QuickTime,
//                "txt" to Text.Plain,
//                "css" to Text.CSS,
//                "csv" to Text.CSV,
//                "htm" to Text.Html,
//                "html" to Text.Html,
//                "js" to Text.JavaScript,
//                "xml" to Text.Xml,
//                "gif" to Image.GIF,
//                "jpg" to Image.JPEG,
//                "jpeg" to Image.JPEG,
//                "png" to Image.PNG,
//                "svg" to Image.SVG,
//                "webp" to Image.WebP,
//                "jp2" to Image.JPEG2000,
//                "pdf" to Application.Pdf,
//                "xlsx" to Application.Xlsx,
//                "docx" to Application.Docx,
//                "pptx" to Application.Pptx,
//            )
//        }
//        private val toFileExtension by lazy { fromFileExtension.entries.associate { it.value to it.key } }
//        fun fromExtension(extension: String) = fromFileExtension[extension.lowercase()] ?: Application.OctetStream
//    }
//
//    val extension: String? get() = toFileExtension[this]
//    val isText: Boolean
//        get() = when (this) {
//            Application.Json -> true
//            Application.Xml -> true
//            Application.JavaScript -> true
//            Application.ProblemJson -> true
//            Application.ProblemXml -> true
//            else -> this.type == "text"
//        }
//}