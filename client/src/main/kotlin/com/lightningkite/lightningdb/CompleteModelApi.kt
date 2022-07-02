@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import java.util.*

abstract class CompleteModelApi<Model: IsCodableAndHashable>{
    abstract val read: ReadModelApi<Model>
    abstract val write: WriteModelApi<Model>
    abstract val observe: ObserveModelApi<Model>
}