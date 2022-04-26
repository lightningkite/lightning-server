package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorkmongo.Database

var database: Database by SetOnce()