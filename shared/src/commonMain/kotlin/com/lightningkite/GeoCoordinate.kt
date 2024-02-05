package com.lightningkite

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

@Serializable(GeoCoordinateGeoJsonSerializer::class)
data class GeoCoordinate(val latitude: Double, val longitude: Double) {
    infix fun distanceToKilometers(other: GeoCoordinate): Double = this.distanceToMiles(other) * 1.609344
    infix fun distanceToMiles(other: GeoCoordinate): Double {
        val theta: Double = this.longitude - other.longitude
        var dist: Double = (sin(deg2rad(this.latitude))
                * sin(deg2rad(other.latitude))
                + (cos(deg2rad(this.latitude))
                * cos(deg2rad(other.latitude))
                * cos(deg2rad(theta))))
        dist = acos(dist)
        dist = rad2deg(dist)
        dist *= 60 * 1.1515
        return dist
    }
}

object GeoCoordinateGeoJsonSerializer: KSerializer<GeoCoordinate> {

    val das = DoubleArraySerializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.lightningkite.GeoCoordinate") {
        element("type", String.serializer().descriptor)
        element("coordinates", das.descriptor)
    }

    override fun deserialize(decoder: Decoder): GeoCoordinate {
        return decoder.decodeStructure(descriptor) {
            var lat = 0.0
            var lon = 0.0
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> decodeStringElement(descriptor, 0)
                    1 -> decodeSerializableElement(descriptor, 1, das).let {
                        lat = it[1]
                        lon = it[0]
                    }
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            GeoCoordinate(lat, lon)
        }
    }

    override fun serialize(encoder: Encoder, value: GeoCoordinate) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, "Point")
            encodeSerializableElement(descriptor, 1, das, doubleArrayOf(value.longitude, value.latitude))
        }
    }

}
object GeoCoordinateArraySerializer: KSerializer<GeoCoordinate> {
    val delegate = DoubleArraySerializer()

    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.GeoCoordinate.array", delegate.descriptor)

    override fun deserialize(decoder: Decoder): GeoCoordinate {
        return decoder.decodeSerializableValue(delegate).let { GeoCoordinate(longitude = it[0], latitude = it[1]) }
    }

    override fun serialize(encoder: Encoder, value: GeoCoordinate) {
        encoder.encodeSerializableValue(delegate, doubleArrayOf(value.longitude, value.latitude))
    }

}

private fun deg2rad(deg: Double): Double {
    return deg * PI / 180.0
}

private fun rad2deg(rad: Double): Double {
    return rad * 180.0 / PI
}