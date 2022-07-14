package com.lightningkite.lightningserver.core

data class ContentType(val type: String, val subtype: String) {
    constructor(string: String) : this(
        string.substringBefore('/'),
        string.substringAfter('/').takeWhile { !it.isWhitespace() && it != ';' })

    override fun toString(): String = "$type/$subtype"

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
        public val JPEG: ContentType = ContentType("image", "jpeg")
        public val PNG: ContentType = ContentType("image", "png")
        public val SVG: ContentType = ContentType("image", "svg+xml")
        public val XIcon: ContentType = ContentType("image", "x-icon")
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
        public val Plain: ContentType = ContentType("text", "plain")
        public val CSS: ContentType = ContentType("text", "css")
        public val CSV: ContentType = ContentType("text", "csv")
        public val Html: ContentType = ContentType("text", "html")
        public val JavaScript: ContentType = ContentType("text", "javascript")
        public val VCard: ContentType = ContentType("text", "vcard")
        public val Xml: ContentType = ContentType("text", "xml")
        public val EventStream: ContentType = ContentType("text", "event-stream")
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
        val fileExtensions = mapOf(
            Application.Cbor to "cbor",
            Application.Json to "json",
            Application.Bson to "bson",
            Application.JavaScript to "js",
            Application.Xml to "xml",
            Application.Zip to "zip",
            Audio.MP4 to "mp4",
            Audio.MPEG to "mpeg",
            Audio.OGG to "ogg",
            Video.MPEG to "mpeg",
            Video.MP4 to "mp4",
            Video.OGG to "ogg",
            Video.QuickTime to "mov",
            Text.Plain to "txt",
            Text.CSS to "css",
            Text.CSV to "csv",
            Text.Html to "html",
            Text.JavaScript to "js",
            Text.Xml to "xml",
            Image.GIF to "gif",
            Image.JPEG to "jpg",
            Image.PNG to "png",
            Image.SVG to "svg",
        )
    }
}