source              = "../domain"
deployment_location = "us-west-2"
deployment_name     = "example"
domain_name         = "example.demo.ivieleague.com"
domain_name_zone    = "ivieleague.com"
debug               = true
lambda_in_vpc       = false
lambda_snapstart    = true
database_org_id     = "6323a65c43d66b56a2ea5aea"
cors                = {
  allowedDomains = ["*"]
  allowedHeaders = ["*"]
}
oauth_google    = null
oauth_apple     = null
reporting_email = "joseph@lightningkite.com"
metrics         = {
  url                  = "db://database"
  trackingByEntryPoint = [
    "Health Checks Run",
    "Execution Time",
    "database Wait Time",
    "database Call Count",
    "cache Wait Time",
    "cache Call Count",
  ]
  trackingTotalsOnly = []
}
exceptions = {
  "url": "sentry://https://4a525067087840fa9bc8b66b0793b2f4@sentry9.lightningkite.com/69"
}
emergencyContact = "joseph@lightningkite.com"
#  files_expiry = null
lambda_timeout = 45
logging = {
  default = {
    filePattern = null
    level = "DEBUG"
    toConsole = true
  }
}
