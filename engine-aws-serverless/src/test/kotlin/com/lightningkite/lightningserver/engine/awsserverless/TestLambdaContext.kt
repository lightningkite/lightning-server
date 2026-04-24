package com.lightningkite.lightningserver.engine.awsserverless

import com.amazonaws.services.lambda.runtime.*

class TestLambdaContext : Context {
    override fun getAwsRequestId(): String = "req-1"
    override fun getLogGroupName(): String = "log-group"
    override fun getLogStreamName(): String = "log-stream"
    override fun getFunctionName(): String = "function"
    override fun getFunctionVersion(): String = "1"
    override fun getInvokedFunctionArn(): String = "arn:aws:lambda:region:acct:function:function"
    override fun getIdentity(): CognitoIdentity? = null
    override fun getClientContext(): ClientContext? = null
    override fun getRemainingTimeInMillis(): Int = 60_000
    override fun getMemoryLimitInMB(): Int = 256
    override fun getLogger(): LambdaLogger = object : LambdaLogger {
        override fun log(message: String?) { /* ignore */
        }

        @Deprecated("Deprecated in AWS SDK recent versions")
        override fun log(message: ByteArray?) { /* ignore */
        }
    }
}