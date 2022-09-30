@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import java.util.*


abstract class FullReadModelApi<Model: IsCodableAndHashable>{
    abstract val read:ReadModelApi<Model>
    abstract val observe:ObserveModelApi<Model>
}