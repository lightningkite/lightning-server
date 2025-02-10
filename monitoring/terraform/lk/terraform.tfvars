// AWS Settings
ip_prefix = "10.0"
domain_name_zone = "cs.lightningkite.com"
domain_name = "monitoring.cs.lightningkite.com"
deployment_location = "us-west-2"

emergencyInvocationsPerMinuteThreshold = null
emergencyComputePerMinuteThreshold = null
panicInvocationsPerMinuteThreshold = null
panicComputePerMinuteThreshold = null
emergencyInvocations = null
emergencyCompute = null
panicInvocations = null
panicCompute = null

rateLimit = false

reporting_email = "joseph@lightningkite.com"
emergencyContact = "joseph@lightningkite.com"

lambda_memory_size = 1024
lambda_timeout = 30
lambda_snapstart = true

// Mongo Settings
database_org_id = "6323a65c43d66b56a2ea5aea"
database_continuous_backup = false

// Server Settings
cors = { allowedDomains: ["*"], allowedHeaders: ["*"] }
debug = true
display_name = "LK Monitoring"
exceptions = {"url":"none"}
files_expiry = "P1D"
metrics = {"url":"log","trackingByEntryPoint":null,"trackingTotalsOnly":null}
serveApp =  null

logging = {
  "default" : {
    "filePattern" : null,
    "toConsole" : true,
    "level" : "DEBUG",
    "additive" : false
  },
  "logger" : {
    "org.mongodb" : {
      "filePattern" : null,
      "toConsole" : false,
      "level" : "INFO",
      "additive" : false
    },
    "software.amazon.awssdk" : {
      "filePattern" : null,
      "toConsole" : false,
      "level" : "INFO",
      "additive" : false
    },
    "io.netty" : {
      "filePattern" : null,
      "toConsole" : false,
      "level" : "INFO",
      "additive" : false
    },
    "com.lightningkite.lightningserver.metrics" : {
      "filePattern" : null,
      "toConsole" : false,
      "level" : "INFO",
      "additive" : false
    }
  }
}