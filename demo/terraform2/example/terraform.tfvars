source              = "../domain"
deployment_location = "us-west-2"
deployment_name     = "example"
domain_name         = "example.demo.ivieleague.com"
domain_name_zone    = "ivieleague.com"
debug               = true
lambda_in_vpc       = false
lambda_snapstart    = false
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
    "executionTime"
  ]
  trackingTotalsOnly = []
}
emergencyContact = "joseph@lightningkite.com"
#  files_expiry = null