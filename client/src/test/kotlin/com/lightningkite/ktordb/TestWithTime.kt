package com.lightningkite.ktordb

import com.lightningkite.rx.okhttp.HttpClient
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.After
import org.junit.Before
import java.util.concurrent.TimeUnit

abstract class TestWithTime {

    val scheduler = TestScheduler()
    val startOffset = 10L
    var currentTime = startOffset

    @Before
    fun ensureClean(){
        HttpClient.responseScheduler = scheduler
        currentTime = startOffset
        scheduler.advanceTimeTo(0, TimeUnit.MILLISECONDS)
    }

    @After
    fun cleanup(){
        HttpClient.responseScheduler = null
    }

    fun advanceTime(by: Long){
        var remaining = by
        while(remaining > 1000L){
            println("Advancing time by a second")
            scheduler.advanceTimeBy(1000L, TimeUnit.MILLISECONDS)
            currentTime += 1000L
            remaining -= 1000L
        }
        println("Advancing time by ${remaining.toDouble()/1000.0} seconds")
        scheduler.advanceTimeBy(remaining, TimeUnit.MILLISECONDS)
        currentTime += remaining
    }
}