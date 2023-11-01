package com.lightningkite

import java.util.Base64


/**
 * Turns a ByteArray into a String containing the Hex Representation of the ByteArray
 * @receiver The ByteArray you wish to turn into a HexString
 * @return Returns a String containing the Hex Representation of the receiver.
 */
@OptIn(ExperimentalUnsignedTypes::class)
fun ByteArray.toHexString(): String = asUByteArray().joinToString("") { it.toString(radix = 16).padStart(2, '0') }


/**
 * Turns a String contain a Hex Value into a ByteArray
 * @receiver The String with a Hex Representation you wish to turn into a ByteArray
 * @return Returns a ByteArray containing the values from the Hex Representation of the receiver.
 */
fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) {
    substring(it * 2, it * 2 + 1).toInt(radix = 16).toByte()
}

fun ByteArray.toBase64() = Base64.getEncoder().encodeToString(this)
fun String.fromBase64() = Base64.getDecoder().decode(this)
fun ByteArray.toBase64Mime() = Base64.getMimeEncoder().encodeToString(this)
fun String.fromBase64Mime() = Base64.getMimeDecoder().decode(this)
fun ByteArray.toBase64Url() = Base64.getUrlEncoder().encodeToString(this)
fun String.fromBase64Url() = Base64.getUrlDecoder().decode(this)
