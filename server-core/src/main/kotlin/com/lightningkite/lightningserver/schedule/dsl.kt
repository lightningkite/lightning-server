package com.lightningkite.lightningserver.schedule

import com.lightningkite.lightningserver.core.LightningServerDsl
import java.time.Duration
import java.time.LocalTime

@LightningServerDsl
fun schedule(name: String, frequency: Duration, action: suspend ()->Unit) {
    Scheduler.schedules[name] = ScheduledTask(name, Schedule.Frequency(frequency), action)
}

@LightningServerDsl
fun schedule(name:String, time: LocalTime, action: suspend () -> Unit){
    Scheduler.schedules[name] = ScheduledTask(name, Schedule.Daily(time), action)
}