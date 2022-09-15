##########
# main
##########
variable "domain_name_zone" {
    type = string
}
variable "domain_name" {
    type = string
}
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
variable "secure_over_cheap" {
    type = bool
    default = false
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
variable "database_min_capacity" {
    type = number
    default = 0.5
}
variable "database_max_capacity" {
    type = number
    default = 2
}
variable "database_auto_pause" {
    type = bool
    default = true
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

