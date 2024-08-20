package com.lightningkite.lightningdb

import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import org.jetbrains.kotlin.psi.KtFile

val KSTypeReference.isMarkedNullable: Boolean get() {
    return hack(this)
}

private val hack: (KSTypeReference)->Boolean by lazy {
    val kClass = Class.forName("com.google.devtools.ksp.symbol.impl.kotlin.KSTypeReferenceImpl")
    val getTypeReference = kClass.getMethod("getKtTypeReference")
    val kClass2 = Class.forName("org.jetbrains.kotlin.psi.KtTypeReference")
    val getTypeElement = kClass2.getMethod("getTypeElement")
    val correctType = Class.forName("org.jetbrains.kotlin.psi.KtNullableType")
    return@lazy {
        kClass.isInstance(it) && correctType.isInstance(getTypeElement(getTypeReference(it)))
    }
}

val KSFile.ktFile: KtFile?
    get() = hack2(this)

private val hack2: (KSFile)->KtFile? by lazy {
    val kClass = Class.forName("com.google.devtools.ksp.symbol.impl.kotlin.KSFileImpl")
    val getTypeReference = kClass.getMethod("getFile")
    return@lazy {
        if (kClass.isInstance(it)) getTypeReference.invoke(it) as KtFile else null
    }
}

val KSValueParameter.defaultText: String?
    get() = hack3(this)

private val hack3: (KSValueParameter)->String? by lazy {
    try {
        val jclass = Class.forName("com.google.devtools.ksp.symbol.impl.kotlin.KSValueParameterImpl")
        val getKtParameter = jclass.getMethod("getKtParameter")
        val jclass2 = Class.forName("org.jetbrains.kotlin.psi.KtParameter")
        val getDefaultValue = jclass2.getMethod("getDefaultValue")
        val jclass3 = Class.forName("org.jetbrains.kotlin.psi.KtExpression")
        val getText = jclass3.getMethod("getText")
        return@lazy out@{
            try {
                if (!it.hasDefault) return@out null
                val x = if (jclass.isInstance(it)) getKtParameter(it) else return@out null
                val y = if (jclass2.isInstance(x)) getDefaultValue(x) else return@out null
                val z = if (jclass3.isInstance(y)) getText(y) else return@out null
                z as? String
            } catch(e: Exception) {
                "/* ${e.stackTraceToString()} */"
            }
        }
    } catch(e: Exception) {
//        throw Exception("Failed to create hack3", e)
        return@lazy { "/* Failed to create hack3: ${e.stackTraceToString()} */" }
    }
}
