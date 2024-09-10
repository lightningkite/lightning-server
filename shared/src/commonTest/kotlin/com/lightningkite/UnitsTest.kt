package com.lightningkite

import com.lightningkite.Length.Companion.miles
import com.lightningkite.Pressure.Companion.psi
import com.lightningkite.Speed.Companion.feetPerSecond
import com.lightningkite.Volume.Companion.cups
import com.lightningkite.Volume.Companion.teaspoons
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class UnitsTest {

    @Test fun test() {
        32.psi.bars
        println(42.feetPerSecond)
        println(1.cups.tablespoons)
        val tollHouseCookies = mapOf(
            "all-purpose flour" to (2.0 + 1.0/4).cups,
            "baking soda" to (1.0).teaspoons,
            "salt" to (1.0).teaspoons,
            "(2 sticks) butter, softened" to (1.0).cups,
            "granulated sugar" to (3.0/4).cups,
            "packed brown sugar" to (3.0/4).cups,
            "vanilla extract" to (1.0).teaspoons,
            "eggs" to (2.0).cups,
            "(12-oz. pkg.) Nestlé Toll House Semi-Sweet Chocolate Morsels" to (2.0).cups,
            "chopped nuts (if omitting, add 1-2 tablespoons of all-purpose flour)" to (1.0).cups,
        )
        tollHouseCookies.entries.forEach {
            println("${it.key}: ${it.value * 2}")
        }

        val speed = 32.miles / 5.minutes
        speed.metersPerSecond
    }
}