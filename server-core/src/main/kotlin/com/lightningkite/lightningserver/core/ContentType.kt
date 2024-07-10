package com.lightningkite.lightningserver.core


/**
 * Holds the content type for both an HttpRequest and an HttpResponse.
 * To learn more about the content type standard: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Type
 *
 * All the common ContentTypes are already provided.
 */
class ContentType(val type: String, val subtype: String, val parameters: Map<String, String> = mapOf()) {
    constructor(string: String) : this(
        string.substringBefore('/'),
        string.substringAfter('/', "*").takeWhile { !it.isWhitespace() && it != ';' },
        string.substringAfter(';', "").split(";")
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    )

    fun matches(other: ContentType): Boolean {
        return (other.type == "*" || type == other.type) &&
                (other.subtype == "*" || subtype == other.subtype)
    }

    override fun equals(other: Any?): Boolean {
        return other is ContentType && type == other.type && subtype == other.subtype
    }

    override fun toString(): String =
        "$type/$subtype" + if (parameters.isEmpty()) "" else parameters.entries.joinToString(
            ";",
            ";"
        ) { it.key + "=" + it.value }

    override fun hashCode(): Int {
        return 48192 + (type.hashCode() shl 16) + subtype.hashCode()
    }

    /**
     * Provides a list of standard subtypes of an `application` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Application {
        /**
         * Represents a pattern `application / *` to match any application content type.
         */
        public val Any: ContentType = ContentType("application", "*")
        public val Atom: ContentType = ContentType("application", "atom+xml")
        public val Cbor: ContentType = ContentType("application", "cbor")
        public val Json: ContentType = ContentType("application", "json")
        public val Bson: ContentType = ContentType("application", "bson")
        public val HalJson: ContentType = ContentType("application", "hal+json")
        public val JavaScript: ContentType = ContentType("application", "javascript")
        public val OctetStream: ContentType = ContentType("application", "octet-stream")
        public val FontWoff: ContentType = ContentType("application", "font-woff")
        public val Rss: ContentType = ContentType("application", "rss+xml")
        public val Xml: ContentType = ContentType("application", "xml")
        public val Xml_Dtd: ContentType = ContentType("application", "xml-dtd")
        public val Zip: ContentType = ContentType("application", "zip")
        public val GZip: ContentType = ContentType("application", "gzip")

        public val FormUrlEncoded: ContentType =
            ContentType("application", "x-www-form-urlencoded")

        public val Pdf: ContentType = ContentType("application", "pdf")
        public val Xlsx: ContentType = ContentType(
            "application",
            "vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
        public val Docx: ContentType = ContentType(
            "application",
            "vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        public val Pptx: ContentType = ContentType(
            "application",
            "vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        public val ProtoBuf: ContentType = ContentType("application", "protobuf")
        public val Wasm: ContentType = ContentType("application", "wasm")
        public val ProblemJson: ContentType = ContentType("application", "problem+json")
        public val ProblemXml: ContentType = ContentType("application", "problem+xml")
    }

    /**
     * Provides a list of standard subtypes of an `audio` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Audio {
        public val Any: ContentType = ContentType("audio", "*")
        public val WAV: ContentType = ContentType("audio", "wav")
        public val MP3: ContentType = ContentType("audio", "mp3")
        public val MP4: ContentType = ContentType("audio", "mp4")
        public val MPEG: ContentType = ContentType("audio", "mpeg")
        public val OGG: ContentType = ContentType("audio", "ogg")
    }

    /**
     * Provides a list of standard subtypes of an `image` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Image {
        public val Any: ContentType = ContentType("image", "*")
        public val GIF: ContentType = ContentType("image", "gif")
        public val JPEG2000: ContentType = ContentType("image", "jp2")
        public val JPEG: ContentType = ContentType("image", "jpeg")
        public val PNG: ContentType = ContentType("image", "png")
        public val SVG: ContentType = ContentType("image", "svg+xml")
        public val WebP: ContentType = ContentType("image", "webp")
        public val XIcon: ContentType = ContentType("image", "x-icon")
        public val Tiff: ContentType = ContentType("image", "tiff")
    }

    /**
     * Provides a list of standard subtypes of a `message` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Message {
        public val Any: ContentType = ContentType("message", "*")
        public val Http: ContentType = ContentType("message", "http")
    }

    /**
     * Provides a list of standard subtypes of a `multipart` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object MultiPart {
        public val Any: ContentType = ContentType("multipart", "*")
        public val Mixed: ContentType = ContentType("multipart", "mixed")
        public val Alternative: ContentType = ContentType("multipart", "alternative")
        public val Related: ContentType = ContentType("multipart", "related")
        public val FormData: ContentType = ContentType("multipart", "form-data")
        public val Signed: ContentType = ContentType("multipart", "signed")
        public val Encrypted: ContentType = ContentType("multipart", "encrypted")
        public val ByteRanges: ContentType = ContentType("multipart", "byteranges")
    }

    /**
     * Provides a list of standard subtypes of a `text` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Text {
        public val Any: ContentType = ContentType("text", "*")
        public val Plain: ContentType = ContentType("text", "plain", mapOf("charset" to "UTF-8"))
        public val CSS: ContentType = ContentType("text", "css", mapOf("charset" to "UTF-8"))
        public val CSV: ContentType = ContentType("text", "csv", mapOf("charset" to "UTF-8"))
        public val Html: ContentType = ContentType("text", "html", mapOf("charset" to "UTF-8"))
        public val JavaScript: ContentType = ContentType("text", "javascript", mapOf("charset" to "UTF-8"))
        public val VCard: ContentType = ContentType("text", "vcard", mapOf("charset" to "UTF-8"))
        public val Xml: ContentType = ContentType("text", "xml", mapOf("charset" to "UTF-8"))
        public val EventStream: ContentType = ContentType("text", "event-stream", mapOf("charset" to "UTF-8"))
        public val UriList: ContentType = ContentType("text", "uri-list", mapOf("charset" to "UTF-8"))
    }

    /**
     * Provides a list of standard subtypes of a `video` content type.
     */
    @Suppress("KDocMissingDocumentation", "unused")
    public object Video {
        public val Any: ContentType = ContentType("video", "*")
        public val MPEG: ContentType = ContentType("video", "mpeg")
        public val MP4: ContentType = ContentType("video", "mp4")
        public val OGG: ContentType = ContentType("video", "ogg")
        public val QuickTime: ContentType = ContentType("video", "quicktime")
    }

    companion object {
        val xmlTypes = setOf(
            Application.Xml,
            Text.Html,
            Text.Xml,
        )
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
}