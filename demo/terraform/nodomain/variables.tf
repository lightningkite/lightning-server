##########
# main
##########
variable "deployment_location" {
    type = string
    default = "us-west-2"
}
variable "deployment_name" {
    type = string
}
variable "debug" {
    type = bool
    default = false
}
variable "lambda_in_vpc" {
    type = bool
    default = true
}
variable "ip_prefix" {
    type = string
    default = "10.0"
}

##########
# general
##########
variable "cors" {
    type = object({ allowedDomains = list(string), allowedHeaders = list(string) })
    default = null
}
variable "display_name" {
    type = string
    default = "demo"
}

##########
# database
##########
variable "database_org_id" {
    type = string
}

##########
# jwt
##########
variable "jwt_expirationMilliseconds" {
    type = number
    default = 31540000000
}
variable "jwt_emailExpirationMilliseconds" {
    type = number
    default = 1800000
}

##########
# oauth_github
##########
variable "oauth_github" {
    type = any
    default = null
}

##########
# logging
##########
variable "logging" {
    type = any
    default = {}
}

##########
# files
##########
variable "files_expiry" {
    type = string
    default = "P1D"
}

##########
# metrics
##########
variable "metrics" {
    type = any
    default = {}
}

##########
# exceptions
##########
variable "exceptions" {
    type = any
    default = {}
}

##########
# email
##########
variable "email_sender" {
    type = string
}

##########
# oauth_google
##########
variable "oauth_google" {
    type = any
    default = null
}

##########
# oauth_apple
##########
variable "oauth_apple" {
    type = any
    default = null
}

##########
# Alarms
##########
variable "emergencyInvocationsPerMinuteThreshold" {
    type = number
    default = 100
}
variable "emergencyComputePerMinuteThreshold" {
    type = number
    default = 10000
}
variable "panicInvocationsPerMinuteThreshold" {
    type = number
    default = 500
}
variable "panicComputePerMinuteThreshold" {
    type = number
    default = 50000
}
variable "emergencyContact" {
    type = string
}

##########
# Main
##########
variable "lambda_memory_size" {
    type = number
    default = 1024
}
variable "lambda_timeout" {
    type = number
    default = 30
}

