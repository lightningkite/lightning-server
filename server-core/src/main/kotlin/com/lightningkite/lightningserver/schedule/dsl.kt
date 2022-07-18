package com.lightningkite.lightningserver.schedule

import com.lightningkite.lightningserver.core.LightningServerDsl
import java.time.Duration

@LightningServerDsl
fun schedule(name: String, frequency: Duration, action: suspend ()->Unit) = Scheduler.schedules.add(ScheduledTask(name, Schedule.Frequency(frequency), action))