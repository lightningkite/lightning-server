package com.lightningkite

import com.lightningkite.Length.Companion.feet
import com.lightningkite.Length.Companion.yards
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit



@JvmInline
@Serializable
value class Length(val meters: Double) {
    val astronomicalUnits: Double get() = meters / 1.495978707E11
    val centimeters: Double get() = meters / 0.01
    val feet: Double get() = meters / 0.3048
    val inches: Double get() = meters / 0.025400000000000002
    val kilometers: Double get() = meters / 1000.0
    val lightYears: Double get() = meters / 9.4607304725808E15
    val micrometer: Double get() = meters / 1.0E-6
    val miles: Double get() = meters / 1609.344
    val millimeter: Double get() = meters / 0.001
    val nanometer: Double get() = meters / 1.0E-9
    val yards: Double get() = meters / 0.9144
    operator fun plus(other: Length): Length = Length(meters + other.meters)
    operator fun minus(other: Length): Length = Length(meters - other.meters)
    operator fun times(ratio: Double): Length = Length(meters * ratio)
    operator fun times(ratio: Int): Length = Length(meters * ratio)
    operator fun div(ratio: Double): Length = Length(meters / ratio)
    operator fun div(ratio: Int): Length = Length(meters / ratio)

    companion object {
        val Int.astronomicalUnits: Length get() = Length(meters = this * 1.495978707E11)
        val Long.astronomicalUnits: Length get() = Length(meters = this * 1.495978707E11)
        val Double.astronomicalUnits: Length get() = Length(meters = this * 1.495978707E11)
        val Int.centimeters: Length get() = Length(meters = this * 0.01)
        val Long.centimeters: Length get() = Length(meters = this * 0.01)
        val Double.centimeters: Length get() = Length(meters = this * 0.01)
        val Int.feet: Length get() = Length(meters = this * 0.3048)
        val Long.feet: Length get() = Length(meters = this * 0.3048)
        val Double.feet: Length get() = Length(meters = this * 0.3048)
        val Int.inches: Length get() = Length(meters = this * 0.025400000000000002)
        val Long.inches: Length get() = Length(meters = this * 0.025400000000000002)
        val Double.inches: Length get() = Length(meters = this * 0.025400000000000002)
        val Int.kilometers: Length get() = Length(meters = this * 1000.0)
        val Long.kilometers: Length get() = Length(meters = this * 1000.0)
        val Double.kilometers: Length get() = Length(meters = this * 1000.0)
        val Int.lightYears: Length get() = Length(meters = this * 9.4607304725808E15)
        val Long.lightYears: Length get() = Length(meters = this * 9.4607304725808E15)
        val Double.lightYears: Length get() = Length(meters = this * 9.4607304725808E15)
        val Int.meters: Length get() = Length(meters = this * 1.0)
        val Long.meters: Length get() = Length(meters = this * 1.0)
        val Double.meters: Length get() = Length(meters = this * 1.0)
        val Int.micrometer: Length get() = Length(meters = this * 1.0E-6)
        val Long.micrometer: Length get() = Length(meters = this * 1.0E-6)
        val Double.micrometer: Length get() = Length(meters = this * 1.0E-6)
        val Int.miles: Length get() = Length(meters = this * 1609.344)
        val Long.miles: Length get() = Length(meters = this * 1609.344)
        val Double.miles: Length get() = Length(meters = this * 1609.344)
        val Int.millimeter: Length get() = Length(meters = this * 0.001)
        val Long.millimeter: Length get() = Length(meters = this * 0.001)
        val Double.millimeter: Length get() = Length(meters = this * 0.001)
        val Int.nanometer: Length get() = Length(meters = this * 1.0E-9)
        val Long.nanometer: Length get() = Length(meters = this * 1.0E-9)
        val Double.nanometer: Length get() = Length(meters = this * 1.0E-9)
        val Int.yards: Length get() = Length(meters = this * 0.9144)
        val Long.yards: Length get() = Length(meters = this * 0.9144)
        val Double.yards: Length get() = Length(meters = this * 0.9144)
    }
    override fun toString(): String = toStringMetric()
    fun toStringMetric(): String = when(meters) {
        in 0.0..<0.0000001 -> "$nanometer nm"
        in 0.0000001..<0.00001 -> "$micrometer mm"
        in 0.0001..<0.01 -> "$millimeter mm"
        in 0.01..<1.0 -> "$centimeters cm"
        in 1.0..<1000.0 -> "$meters m"
        in 1000.0..<9.4607304725808E13 -> "$kilometers km"
        else -> "$lightYears ly"
    }
    fun toStringImperial(): String = when(meters) {
        in 0.0..<2.feet.meters -> "$inches in"
        in 1.feet.meters..<5.yards.meters -> "$feet ft"
        in 5.yards.meters..<600.yards.meters -> "$feet yd"
        in 600.yards.meters..<9.4607304725808E13 -> "$miles mi"
        else -> "$lightYears ly"
    }
}

@JvmInline
@Serializable
value class Area(val squareMeters: Double) {
    val acres: Double get() = squareMeters / 4046.8564224
    val hectare: Double get() = squareMeters / 10000.0
    val squareKilometers: Double get() = squareMeters / 1000000.0
    val squareMillimeters: Double get() = squareMeters / 1.0E-6
    val squareCentimeters: Double get() = squareMeters / 1.0E-4
    operator fun plus(other: Area): Area = Area(squareMeters + other.squareMeters)
    operator fun minus(other: Area): Area = Area(squareMeters - other.squareMeters)
    operator fun times(ratio: Double): Area = Area(squareMeters * ratio)
    operator fun times(ratio: Int): Area = Area(squareMeters * ratio)
    operator fun div(ratio: Double): Area = Area(squareMeters / ratio)
    operator fun div(ratio: Int): Area = Area(squareMeters / ratio)

    companion object {
        val Int.acres: Area get() = Area(squareMeters = this * 4046.8564224)
        val Long.acres: Area get() = Area(squareMeters = this * 4046.8564224)
        val Double.acres: Area get() = Area(squareMeters = this * 4046.8564224)
        val Int.hectare: Area get() = Area(squareMeters = this * 10000.0)
        val Long.hectare: Area get() = Area(squareMeters = this * 10000.0)
        val Double.hectare: Area get() = Area(squareMeters = this * 10000.0)
        val Int.squareKilometers: Area get() = Area(squareMeters = this * 1000000.0)
        val Long.squareKilometers: Area get() = Area(squareMeters = this * 1000000.0)
        val Double.squareKilometers: Area get() = Area(squareMeters = this * 1000000.0)
        val Int.squareMeters: Area get() = Area(squareMeters = this * 1.0)
        val Long.squareMeters: Area get() = Area(squareMeters = this * 1.0)
        val Double.squareMeters: Area get() = Area(squareMeters = this * 1.0)
        val Int.squareCentimeters: Area get() = Area(squareMeters = this * 1.0E-4)
        val Long.squareCentimeters: Area get() = Area(squareMeters = this * 1.0E-4)
        val Double.squareCentimeters: Area get() = Area(squareMeters = this * 1.0E-4)
        val Int.squareMillimeters: Area get() = Area(squareMeters = this * 1.0E-6)
        val Long.squareMillimeters: Area get() = Area(squareMeters = this * 1.0E-6)
        val Double.squareMillimeters: Area get() = Area(squareMeters = this * 1.0E-6)
    }
    override fun toString(): String = "$squareMeters m^2"
}

@JvmInline
@Serializable
value class Volume(val cubicMeters: Double) {
    val cubicFeet: Double get() = cubicMeters / 0.028316846592000004
    val cubicKilometers: Double get() = cubicMeters / 1.0E9
    val cubicYards: Double get() = cubicMeters / 0.764554857984
    val cups: Double get() = cubicMeters / 2.365882365000001E-4
    val gallons: Double get() = cubicMeters / 0.0037854117840000014
    val liquidOunces: Double get() = cubicMeters / 2.957352956250001E-5
    val liters: Double get() = cubicMeters / 0.001
    val milliliters: Double get() = cubicMeters / 1.0E-6
    val pints: Double get() = cubicMeters / 4.731764730000002E-4
    val quarts: Double get() = cubicMeters / 9.463529460000004E-4
    val tablespoons: Double get() = cubicMeters / 1.4786764781250006E-5
    val teaspoons: Double get() = cubicMeters / 4.928921593750002E-6
    operator fun plus(other: Volume): Volume = Volume(cubicMeters + other.cubicMeters)
    operator fun minus(other: Volume): Volume = Volume(cubicMeters - other.cubicMeters)
    operator fun times(ratio: Double): Volume = Volume(cubicMeters * ratio)
    operator fun times(ratio: Int): Volume = Volume(cubicMeters * ratio)
    operator fun div(ratio: Double): Volume = Volume(cubicMeters / ratio)
    operator fun div(ratio: Int): Volume = Volume(cubicMeters / ratio)

    companion object {
        val Int.cubicFeet: Volume get() = Volume(cubicMeters = this * 0.028316846592000004)
        val Long.cubicFeet: Volume get() = Volume(cubicMeters = this * 0.028316846592000004)
        val Double.cubicFeet: Volume get() = Volume(cubicMeters = this * 0.028316846592000004)
        val Int.cubicKilometers: Volume get() = Volume(cubicMeters = this * 1.0E9)
        val Long.cubicKilometers: Volume get() = Volume(cubicMeters = this * 1.0E9)
        val Double.cubicKilometers: Volume get() = Volume(cubicMeters = this * 1.0E9)
        val Int.cubicMeters: Volume get() = Volume(cubicMeters = this * 1.0)
        val Long.cubicMeters: Volume get() = Volume(cubicMeters = this * 1.0)
        val Double.cubicMeters: Volume get() = Volume(cubicMeters = this * 1.0)
        val Int.cubicYards: Volume get() = Volume(cubicMeters = this * 0.764554857984)
        val Long.cubicYards: Volume get() = Volume(cubicMeters = this * 0.764554857984)
        val Double.cubicYards: Volume get() = Volume(cubicMeters = this * 0.764554857984)
        val Int.cups: Volume get() = Volume(cubicMeters = this * 2.365882365000001E-4)
        val Long.cups: Volume get() = Volume(cubicMeters = this * 2.365882365000001E-4)
        val Double.cups: Volume get() = Volume(cubicMeters = this * 2.365882365000001E-4)
        val Int.gallons: Volume get() = Volume(cubicMeters = this * 0.0037854117840000014)
        val Long.gallons: Volume get() = Volume(cubicMeters = this * 0.0037854117840000014)
        val Double.gallons: Volume get() = Volume(cubicMeters = this * 0.0037854117840000014)
        val Int.liquidOunces: Volume get() = Volume(cubicMeters = this * 2.957352956250001E-5)
        val Long.liquidOunces: Volume get() = Volume(cubicMeters = this * 2.957352956250001E-5)
        val Double.liquidOunces: Volume get() = Volume(cubicMeters = this * 2.957352956250001E-5)
        val Int.liters: Volume get() = Volume(cubicMeters = this * 0.001)
        val Long.liters: Volume get() = Volume(cubicMeters = this * 0.001)
        val Double.liters: Volume get() = Volume(cubicMeters = this * 0.001)
        val Int.milliliters: Volume get() = Volume(cubicMeters = this * 1.0E-6)
        val Long.milliliters: Volume get() = Volume(cubicMeters = this * 1.0E-6)
        val Double.milliliters: Volume get() = Volume(cubicMeters = this * 1.0E-6)
        val Int.pints: Volume get() = Volume(cubicMeters = this * 4.731764730000002E-4)
        val Long.pints: Volume get() = Volume(cubicMeters = this * 4.731764730000002E-4)
        val Double.pints: Volume get() = Volume(cubicMeters = this * 4.731764730000002E-4)
        val Int.quarts: Volume get() = Volume(cubicMeters = this * 9.463529460000004E-4)
        val Long.quarts: Volume get() = Volume(cubicMeters = this * 9.463529460000004E-4)
        val Double.quarts: Volume get() = Volume(cubicMeters = this * 9.463529460000004E-4)
        val Int.tablespoons: Volume get() = Volume(cubicMeters = this * 1.4786764781250006E-5)
        val Long.tablespoons: Volume get() = Volume(cubicMeters = this * 1.4786764781250006E-5)
        val Double.tablespoons: Volume get() = Volume(cubicMeters = this * 1.4786764781250006E-5)
        val Int.teaspoons: Volume get() = Volume(cubicMeters = this * 4.928921593750002E-6)
        val Long.teaspoons: Volume get() = Volume(cubicMeters = this * 4.928921593750002E-6)
        val Double.teaspoons: Volume get() = Volume(cubicMeters = this * 4.928921593750002E-6)
    }
    override fun toString(): String = "$cubicMeters m³"
}

@JvmInline
@Serializable
value class Mass(val kilograms: Double) {
    val grains: Double get() = kilograms / 6.479891000000001E-5
    val grams: Double get() = kilograms / 0.001
    val milligrams: Double get() = kilograms / 1.0E-6
    val pounds: Double get() = kilograms / 0.45359237
    val tonnes: Double get() = kilograms / 1000.0
    val tons: Double get() = kilograms / 907.18474
    val weightOunces: Double get() = kilograms / 0.028349523125
    operator fun plus(other: Mass): Mass = Mass(kilograms + other.kilograms)
    operator fun minus(other: Mass): Mass = Mass(kilograms - other.kilograms)
    operator fun times(ratio: Double): Mass = Mass(kilograms * ratio)
    operator fun times(ratio: Int): Mass = Mass(kilograms * ratio)
    operator fun div(ratio: Double): Mass = Mass(kilograms / ratio)
    operator fun div(ratio: Int): Mass = Mass(kilograms / ratio)

    companion object {
        val Int.grains: Mass get() = Mass(kilograms = this * 6.479891000000001E-5)
        val Long.grains: Mass get() = Mass(kilograms = this * 6.479891000000001E-5)
        val Double.grains: Mass get() = Mass(kilograms = this * 6.479891000000001E-5)
        val Int.grams: Mass get() = Mass(kilograms = this * 0.001)
        val Long.grams: Mass get() = Mass(kilograms = this * 0.001)
        val Double.grams: Mass get() = Mass(kilograms = this * 0.001)
        val Int.kilograms: Mass get() = Mass(kilograms = this * 1.0)
        val Long.kilograms: Mass get() = Mass(kilograms = this * 1.0)
        val Double.kilograms: Mass get() = Mass(kilograms = this * 1.0)
        val Int.milligrams: Mass get() = Mass(kilograms = this * 1.0E-6)
        val Long.milligrams: Mass get() = Mass(kilograms = this * 1.0E-6)
        val Double.milligrams: Mass get() = Mass(kilograms = this * 1.0E-6)
        val Int.pounds: Mass get() = Mass(kilograms = this * 0.45359237)
        val Long.pounds: Mass get() = Mass(kilograms = this * 0.45359237)
        val Double.pounds: Mass get() = Mass(kilograms = this * 0.45359237)
        val Int.tonnes: Mass get() = Mass(kilograms = this * 1000.0)
        val Long.tonnes: Mass get() = Mass(kilograms = this * 1000.0)
        val Double.tonnes: Mass get() = Mass(kilograms = this * 1000.0)
        val Int.tons: Mass get() = Mass(kilograms = this * 907.18474)
        val Long.tons: Mass get() = Mass(kilograms = this * 907.18474)
        val Double.tons: Mass get() = Mass(kilograms = this * 907.18474)
        val Int.weightOunces: Mass get() = Mass(kilograms = this * 0.028349523125)
        val Long.weightOunces: Mass get() = Mass(kilograms = this * 0.028349523125)
        val Double.weightOunces: Mass get() = Mass(kilograms = this * 0.028349523125)
    }
    override fun toString(): String = "$kilograms kg"
}

@JvmInline
@Serializable
value class Speed(val metersPerSecond: Double) {
    val feetPerSecond: Double get() = metersPerSecond / 0.3048
    val kilometersPerHour: Double get() = metersPerSecond / 0.2777777777777778
    val milesPerHour: Double get() = metersPerSecond / 0.44704
    operator fun plus(other: Speed): Speed = Speed(metersPerSecond + other.metersPerSecond)
    operator fun minus(other: Speed): Speed = Speed(metersPerSecond - other.metersPerSecond)
    operator fun times(ratio: Double): Speed = Speed(metersPerSecond * ratio)
    operator fun times(ratio: Int): Speed = Speed(metersPerSecond * ratio)
    operator fun div(ratio: Double): Speed = Speed(metersPerSecond / ratio)
    operator fun div(ratio: Int): Speed = Speed(metersPerSecond / ratio)

    companion object {
        val Int.feetPerSecond: Speed get() = Speed(metersPerSecond = this * 0.3048)
        val Long.feetPerSecond: Speed get() = Speed(metersPerSecond = this * 0.3048)
        val Double.feetPerSecond: Speed get() = Speed(metersPerSecond = this * 0.3048)
        val Int.kilometersPerHour: Speed get() = Speed(metersPerSecond = this * 0.2777777777777778)
        val Long.kilometersPerHour: Speed get() = Speed(metersPerSecond = this * 0.2777777777777778)
        val Double.kilometersPerHour: Speed get() = Speed(metersPerSecond = this * 0.2777777777777778)
        val Int.metersPerSecond: Speed get() = Speed(metersPerSecond = this * 1.0)
        val Long.metersPerSecond: Speed get() = Speed(metersPerSecond = this * 1.0)
        val Double.metersPerSecond: Speed get() = Speed(metersPerSecond = this * 1.0)
        val Int.milesPerHour: Speed get() = Speed(metersPerSecond = this * 0.44704)
        val Long.milesPerHour: Speed get() = Speed(metersPerSecond = this * 0.44704)
        val Double.milesPerHour: Speed get() = Speed(metersPerSecond = this * 0.44704)
    }
    override fun toString(): String = "$metersPerSecond m/s"
}

@JvmInline
@Serializable
value class Acceleration(val metersPerSecondPerSecond: Double) {
    val feetPerSecondPerSecond: Double get() = metersPerSecondPerSecond / 0.3048
    val kilometersPerHourPerSecond: Double get() = metersPerSecondPerSecond / 0.2777777777777778
    val milesPerHourPerSecond: Double get() = metersPerSecondPerSecond / 0.44704
    operator fun plus(other: Acceleration): Acceleration = Acceleration(metersPerSecondPerSecond + other.metersPerSecondPerSecond)
    operator fun minus(other: Acceleration): Acceleration = Acceleration(metersPerSecondPerSecond - other.metersPerSecondPerSecond)
    operator fun times(ratio: Double): Acceleration = Acceleration(metersPerSecondPerSecond * ratio)
    operator fun times(ratio: Int): Acceleration = Acceleration(metersPerSecondPerSecond * ratio)
    operator fun div(ratio: Double): Acceleration = Acceleration(metersPerSecondPerSecond / ratio)
    operator fun div(ratio: Int): Acceleration = Acceleration(metersPerSecondPerSecond / ratio)

    companion object {
        val Int.feetPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.3048)
        val Long.feetPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.3048)
        val Double.feetPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.3048)
        val Int.kilometersPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.2777777777777778)
        val Long.kilometersPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.2777777777777778)
        val Double.kilometersPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.2777777777777778)
        val Int.metersPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 1.0)
        val Long.metersPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 1.0)
        val Double.metersPerSecondPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 1.0)
        val Int.milesPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.44704)
        val Long.milesPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.44704)
        val Double.milesPerHourPerSecond: Acceleration get() = Acceleration(metersPerSecondPerSecond = this * 0.44704)
    }
    override fun toString(): String = "$metersPerSecondPerSecond m/s²"
}

@JvmInline
@Serializable
value class Force(val newtons: Double) {
    val poundForce: Double get() = newtons / 4.448222
    operator fun plus(other: Force): Force = Force(newtons + other.newtons)
    operator fun minus(other: Force): Force = Force(newtons - other.newtons)
    operator fun times(ratio: Double): Force = Force(newtons * ratio)
    operator fun times(ratio: Int): Force = Force(newtons * ratio)
    operator fun div(ratio: Double): Force = Force(newtons / ratio)
    operator fun div(ratio: Int): Force = Force(newtons / ratio)

    companion object {
        val Int.newtons: Force get() = Force(newtons = this * 1.0)
        val Long.newtons: Force get() = Force(newtons = this * 1.0)
        val Double.newtons: Force get() = Force(newtons = this * 1.0)
        val Int.poundForce: Force get() = Force(newtons = this * 4.448222)
        val Long.poundForce: Force get() = Force(newtons = this * 4.448222)
        val Double.poundForce: Force get() = Force(newtons = this * 4.448222)
    }
    override fun toString(): String = "$newtons N"
}

@JvmInline
@Serializable
value class Pressure(val pascals: Double) {
    val atmospheres: Double get() = pascals / 101325.0
    val bars: Double get() = pascals / 100000.0
    val millibars: Double get() = pascals / 100.0
    val psi: Double get() = pascals / 6894.757889515778
    operator fun plus(other: Pressure): Pressure = Pressure(pascals + other.pascals)
    operator fun minus(other: Pressure): Pressure = Pressure(pascals - other.pascals)
    operator fun times(ratio: Double): Pressure = Pressure(pascals * ratio)
    operator fun times(ratio: Int): Pressure = Pressure(pascals * ratio)
    operator fun div(ratio: Double): Pressure = Pressure(pascals / ratio)
    operator fun div(ratio: Int): Pressure = Pressure(pascals / ratio)

    companion object {
        val Int.atmospheres: Pressure get() = Pressure(pascals = this * 101325.0)
        val Long.atmospheres: Pressure get() = Pressure(pascals = this * 101325.0)
        val Double.atmospheres: Pressure get() = Pressure(pascals = this * 101325.0)
        val Int.bars: Pressure get() = Pressure(pascals = this * 100000.0)
        val Long.bars: Pressure get() = Pressure(pascals = this * 100000.0)
        val Double.bars: Pressure get() = Pressure(pascals = this * 100000.0)
        val Int.millibars: Pressure get() = Pressure(pascals = this * 100.0)
        val Long.millibars: Pressure get() = Pressure(pascals = this * 100.0)
        val Double.millibars: Pressure get() = Pressure(pascals = this * 100.0)
        val Int.pascals: Pressure get() = Pressure(pascals = this * 1.0)
        val Long.pascals: Pressure get() = Pressure(pascals = this * 1.0)
        val Double.pascals: Pressure get() = Pressure(pascals = this * 1.0)
        val Int.psi: Pressure get() = Pressure(pascals = this * 6894.757889515778)
        val Long.psi: Pressure get() = Pressure(pascals = this * 6894.757889515778)
        val Double.psi: Pressure get() = Pressure(pascals = this * 6894.757889515778)
    }
    override fun toString(): String = "$pascals Pa"
}

@JvmInline
@Serializable
value class Energy(val joules: Double) {
    val btus: Double get() = joules / 9.484516526770049E-4
    val kcal: Double get() = joules / 4.184
    operator fun plus(other: Energy): Energy = Energy(joules + other.joules)
    operator fun minus(other: Energy): Energy = Energy(joules - other.joules)
    operator fun times(ratio: Double): Energy = Energy(joules * ratio)
    operator fun times(ratio: Int): Energy = Energy(joules * ratio)
    operator fun div(ratio: Double): Energy = Energy(joules / ratio)
    operator fun div(ratio: Int): Energy = Energy(joules / ratio)

    companion object {
        val Int.btus: Energy get() = Energy(joules = this * 9.484516526770049E-4)
        val Long.btus: Energy get() = Energy(joules = this * 9.484516526770049E-4)
        val Double.btus: Energy get() = Energy(joules = this * 9.484516526770049E-4)
        val Int.joules: Energy get() = Energy(joules = this * 1.0)
        val Long.joules: Energy get() = Energy(joules = this * 1.0)
        val Double.joules: Energy get() = Energy(joules = this * 1.0)
        val Int.kcal: Energy get() = Energy(joules = this * 4.184)
        val Long.kcal: Energy get() = Energy(joules = this * 4.184)
        val Double.kcal: Energy get() = Energy(joules = this * 4.184)
    }
    override fun toString(): String = "$joules J"
}

@JvmInline
@Serializable
value class Power(val watts: Double) {
    val kilowatts: Double get() = watts / 1000.0
    operator fun plus(other: Power): Power = Power(watts + other.watts)
    operator fun minus(other: Power): Power = Power(watts - other.watts)
    operator fun times(ratio: Double): Power = Power(watts * ratio)
    operator fun times(ratio: Int): Power = Power(watts * ratio)
    operator fun div(ratio: Double): Power = Power(watts / ratio)
    operator fun div(ratio: Int): Power = Power(watts / ratio)

    companion object {
        val Int.kilowatts: Power get() = Power(watts = this * 1000.0)
        val Long.kilowatts: Power get() = Power(watts = this * 1000.0)
        val Double.kilowatts: Power get() = Power(watts = this * 1000.0)
        val Int.watts: Power get() = Power(watts = this * 1.0)
        val Long.watts: Power get() = Power(watts = this * 1.0)
        val Double.watts: Power get() = Power(watts = this * 1.0)
    }
    override fun toString(): String = "$watts W"
}

@JvmInline
@Serializable
value class Temperature(val celsius: Double) {
    val fahrenheit: Double get() = celsius * 9 / 5 + 32
    val kelvin: Double get() = celsius + 273.15
    operator fun plus(other: Temperature): Temperature = Temperature(celsius + other.celsius)
    operator fun minus(other: Temperature): Temperature = Temperature(celsius - other.celsius)

    companion object {
        val Int.celsius: Temperature get() = Temperature(celsius = this * 1.0)
        val Long.celsius: Temperature get() = Temperature(celsius = this * 1.0)
        val Double.celsius: Temperature get() = Temperature(celsius = this * 1.0)
        val Int.fahrenheit: Temperature get() = Temperature(celsius = (this - 32.0) * 5 / 9)
        val Long.fahrenheit: Temperature get() = Temperature(celsius = (this - 32.0) * 5 / 9)
        val Double.fahrenheit: Temperature get() = Temperature(celsius = (this - 32.0) * 5 / 9)
        val Int.kelvin: Temperature get() = Temperature(celsius = this - 273.15)
        val Long.kelvin: Temperature get() = Temperature(celsius = this - 273.15)
        val Double.kelvin: Temperature get() = Temperature(celsius = this - 273.15)
    }
    override fun toString(): String = "$celsius °C"
}

operator fun Acceleration.times(other: Duration): Speed = Speed(metersPerSecond = this.metersPerSecondPerSecond * other.toDouble(DurationUnit.SECONDS))
operator fun Acceleration.times(other: Mass): Force = Force(newtons = this.metersPerSecondPerSecond * other.kilograms)
operator fun Area.div(other: Length): Length = Length(meters = this.squareMeters / other.meters)
operator fun Area.times(other: Length): Volume = Volume(cubicMeters = this.squareMeters * other.meters)
operator fun Area.times(other: Pressure): Force = Force(newtons = this.squareMeters * other.pascals)
operator fun Duration.times(other: Acceleration): Speed = Speed(metersPerSecond = this.toDouble(DurationUnit.SECONDS) * other.metersPerSecondPerSecond)
operator fun Duration.times(other: Power): Energy = Energy(joules = this.toDouble(DurationUnit.SECONDS) * other.watts)
operator fun Duration.times(other: Speed): Length = Length(meters = this.toDouble(DurationUnit.SECONDS) * other.metersPerSecond)
operator fun Energy.div(other: Duration): Power = Power(watts = this.joules / other.toDouble(DurationUnit.SECONDS))
operator fun Energy.div(other: Force): Length = Length(meters = this.joules / other.newtons)
operator fun Energy.div(other: Length): Force = Force(newtons = this.joules / other.meters)
operator fun Energy.div(other: Power): Duration = (this.joules / other.watts).seconds
operator fun Energy.div(other: Pressure): Volume = Volume(cubicMeters = this.joules / other.pascals)
operator fun Energy.div(other: Volume): Pressure = Pressure(pascals = this.joules / other.cubicMeters)
operator fun Force.div(other: Acceleration): Mass = Mass(kilograms = this.newtons / other.metersPerSecondPerSecond)
operator fun Force.div(other: Area): Pressure = Pressure(pascals = this.newtons / other.squareMeters)
operator fun Force.div(other: Mass): Acceleration = Acceleration(metersPerSecondPerSecond = this.newtons / other.kilograms)
operator fun Force.div(other: Pressure): Area = Area(squareMeters = this.newtons / other.pascals)
operator fun Force.times(other: Length): Energy = Energy(joules = this.newtons * other.meters)
operator fun Force.times(other: Speed): Power = Power(watts = this.newtons * other.metersPerSecond)
operator fun Length.div(other: Duration): Speed = Speed(metersPerSecond = this.meters / other.toDouble(DurationUnit.SECONDS))
operator fun Length.div(other: Speed): Duration = (this.meters / other.metersPerSecond).seconds
operator fun Length.times(other: Area): Volume = Volume(cubicMeters = this.meters * other.squareMeters)
operator fun Length.times(other: Force): Energy = Energy(joules = this.meters * other.newtons)
operator fun Length.times(other: Length): Area = Area(squareMeters = this.meters * other.meters)
operator fun Mass.times(other: Acceleration): Force = Force(newtons = this.kilograms * other.metersPerSecondPerSecond)
operator fun Power.div(other: Force): Speed = Speed(metersPerSecond = this.watts / other.newtons)
operator fun Power.div(other: Speed): Force = Force(newtons = this.watts / other.metersPerSecond)
operator fun Power.times(other: Duration): Energy = Energy(joules = this.watts * other.toDouble(DurationUnit.SECONDS))
operator fun Pressure.times(other: Area): Force = Force(newtons = this.pascals * other.squareMeters)
operator fun Pressure.times(other: Volume): Energy = Energy(joules = this.pascals * other.cubicMeters)
operator fun Speed.div(other: Acceleration): Duration = (this.metersPerSecond / other.metersPerSecondPerSecond).seconds
operator fun Speed.div(other: Duration): Acceleration = Acceleration(metersPerSecondPerSecond = this.metersPerSecond / other.toDouble(DurationUnit.SECONDS))
operator fun Speed.times(other: Duration): Length = Length(meters = this.metersPerSecond * other.toDouble(DurationUnit.SECONDS))
operator fun Speed.times(other: Force): Power = Power(watts = this.metersPerSecond * other.newtons)
operator fun Volume.div(other: Area): Length = Length(meters = this.cubicMeters / other.squareMeters)
operator fun Volume.div(other: Length): Area = Area(squareMeters = this.cubicMeters / other.meters)
operator fun Volume.times(other: Pressure): Energy = Energy(joules = this.cubicMeters * other.pascals)