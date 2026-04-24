package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter

class AwsHandler : AwsAdapter(Server.build()) {
}