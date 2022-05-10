@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode


abstract class FullReadModelApi<Model: IsCodableAndHashable>{
    abstract val read:ReadModelApi<Model>
    abstract val observe:ObserveModelApi<Model>
}