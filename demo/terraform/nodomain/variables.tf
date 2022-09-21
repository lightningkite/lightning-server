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

##########
# general
##########
variable "cors" {
    type = object({ allowedDomains = list(string), allowedHeaders = list(string) })
    default = null
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

