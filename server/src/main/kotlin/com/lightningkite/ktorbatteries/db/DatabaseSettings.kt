package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktordb.Database

var database: Database by SetOnce()