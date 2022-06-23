package com.lightningkite.ktordb

import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
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
        if(kClass.isInstance(it)) getTypeReference.invoke(it) as KtFile else null
    }
}
