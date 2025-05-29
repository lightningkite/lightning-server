package com.lightningkite.lightningserver.db

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import java.io.File

private var lastFile: File? = null
private var lastText: String? = null
private fun File.cachedReadText(): String {
    if(lastFile == this) return lastText!!
    lastText = readText()
    lastFile = this
    return lastText!!
}

val KSTypeReference.isMarkedNullable: Boolean get() {
    return resolve().isMarkedNullable
}

val KSFile.imports: List<String>
    get() {
        return File(this.filePath).cachedReadText().lines().mapNotNull {
            it.trim().takeIf { it.startsWith("import ") }?.substringAfter("import ")
        }.toList()
    }

private operator fun Any?.get(key: String): Any? {
    return try {
        this!!::class.java.getDeclaredField(key).also { it.isAccessible = true }.get(this)
    } catch(e: Exception) {
        throw Exception("${this!!::class} looking for $key, but only ${this!!::class.java.declaredFields.joinToString { it.name }} exist")
    }
}
private operator fun Any?.invoke(key: String): Any? {
    return try {
        this!!::class.java.getDeclaredMethod(key).also { it.isAccessible = true }.invoke(this)
    } catch(e: Exception) {
        throw Exception("${this!!::class} looking for $key, but only ${this!!::class.java.declaredMethods.joinToString { it.name }} exist")
    }
}

val KSValueParameter.defaultText: String?
    get() {
        try {
            val c = (this.parent as KSFunctionDeclaration).parent as KSClassDeclaration
            return File(this.containingFile!!.filePath).cachedReadText()
                .fileDefaultTextForValr(c.simpleName.asString(), name!!.asString())
        } catch(e: Exception) {
            return "/*" + e.stackTraceToString() + "*/"
        }
    }

fun String.fileDefaultTextForValr(classname: String, name: String): String? {
    val classnameRegex = Regex("class +$classname[^a-zA-Z]")
    val start = classnameRegex.find(this)?.range?.last ?: return null
    val regex = Regex("va[lr] +${name} *:")
    val text = this
    val find = regex.find(text, start) ?: return null
    val end = text.unwrappedComma(find.range.last)
    return text.substring(find.range.last, end).substringAfter('=', "").trim().takeUnless { it.isBlank() }
}

fun String.unwrappedComma(start: Int): Int {
    var level = 0
    for(index in start..<length) {
        when(this[index]) {
            ',' -> if(level == 0) return index
            '>' -> if(this[index - 1] != '-') level--
            '{', '<', '(' -> level++
            '}', ')' -> if(--level < 0) return index
        }
    }
    return length
}