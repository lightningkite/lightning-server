package com.lightningkite.serialization

import com.lightningkite.serialization.SerializableAnnotationValue
import com.lightningkite.serialization.SerializableProperty
import com.lightningkite.serialization.serializableAnnotations
import com.lightningkite.titleCase
import kotlinx.serialization.KSerializer

val SerializableProperty<*, *>.displayName: String
    get() = this.serializableAnnotations.find { it.fqn == "com.lightningkite.lightningdb.DisplayName" }?.values?.get(
        "text"
    )?.let { it as? SerializableAnnotationValue.StringValue }?.value ?: name.titleCase()
val KSerializer<*>.displayName: String
    get() = serializableAnnotations.find { it.fqn == "com.lightningkite.lightningdb.DisplayName" }?.values?.get(
        "text"
    )?.let { it as? SerializableAnnotationValue.StringValue }?.value ?: descriptor.serialName.substringBefore('<').substringAfterLast('.').titleCase()

val SerializableProperty<*, *>.description: String?
    get() = this.serializableAnnotations.find { it.fqn == "com.lightningkite.lightningdb.Description" }?.values?.get(
        "text"
    )?.let { it as? SerializableAnnotationValue.StringValue }?.value
val KSerializer<*>.description: String?
    get() = serializableAnnotations.find { it.fqn == "com.lightningkite.lightningdb.Description" }?.values?.get(
        "text"
    )?.let { it as? SerializableAnnotationValue.StringValue }?.value

val SerializableProperty<*, *>.descriptionOrDisplayName get() = description ?: displayName
val SerializableProperty<*, *>.hint
    get() = serializableAnnotations.find { it.fqn == "com.lightningkite.lightningdb.Hint" }?.values?.get(
        "text"
    )?.let { it as? SerializableAnnotationValue.StringValue }?.value
        ?: description
        ?: displayName

val SerializableProperty<*, *>.group get() = serializableAnnotations.find {
    it.fqn == "com.lightningkite.lightningdb.Group"
}?.values?.values?.first()?.let {
    it as? SerializableAnnotationValue.StringValue
}?.value
val SerializableProperty<*, *>.sentence get() = serializableAnnotations.find {
    it.fqn == "com.lightningkite.lightningdb.Sentence"
}?.values?.values?.first()?.let {
    it as? SerializableAnnotationValue.StringValue
}?.value
val SerializableProperty<*, *>.importance get() = serializableAnnotations.find {
    it.fqn == "com.lightningkite.lightningdb.Importance"
}?.values?.values?.first()?.let {
    it as? SerializableAnnotationValue.ByteValue
}?.value?.toInt() ?: when (name) {
    "_id" -> if(serializer.descriptor.serialName == "com.lightningkite.UUID") 8 else 1
    "title", "subject" -> 1
    "name", "email", "phone" -> 2
    else -> 7
}
val SerializableProperty<*, *>.doesNotNeedLabel get() = serializableAnnotations.any {
    it.fqn == "com.lightningkite.lightningdb.DoesNotNeedLabel"
}
val SerializableProperty<*, *>.indexed get() = serializableAnnotations.any {
    it.fqn == "com.lightningkite.lightningdb.Index"
}