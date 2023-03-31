package com.lightningkite.lightningserver.bytes


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