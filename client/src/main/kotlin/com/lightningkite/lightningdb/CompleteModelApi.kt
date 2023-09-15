@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.IsCodableAndHashableNotNull
import com.lightningkite.khrysalis.SharedCode
import java.util.*

abstract class CompleteModelApi<Model: IsCodableAndHashableNotNull>{
    abstract val read: ReadModelApi<Model>
    abstract val write: WriteModelApi<Model>
    abstract val observe: ObserveModelApi<Model>
}