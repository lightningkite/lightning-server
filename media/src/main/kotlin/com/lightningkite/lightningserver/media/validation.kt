package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.services.database.validation.AnnotationValidators
import com.lightningkite.services.database.validation.EmptyAnnotationValidators

@Deprecated("No longer does anything")
public val AnnotationValidators.Companion.Media: Runtime<AnnotationValidators> get() = Runtime.Constant(EmptyAnnotationValidators())