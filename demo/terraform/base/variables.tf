variable "deployment_location" {
  default = "us-west-2"
}
variable "deployment_name" {
  default = "test"
}
variable "debug" {
  default = false
}
variable "database_expiry" {
    default = "P1D"
}
variable "cache_node_type" {
  default = "cache.t2.micro"
}
variable "cache_node_count" {
  default = 1
}
variable "jwt_expirationMilliseconds" {
  default = 31540000000
}
variable "jwt_emailExpirationMilliseconds" {
  default = 1800000
}
variable "oauth-google" {
  default = null
}
variable "logging" {
  default = {}
}
variable "files_expiry" {
    default = "P1D"
}
variable "oauth-github" {
  default = null
}
variable "exceptions" {
  default = {}
}
variable "email_sender" {
}
variable "oauth-apple" {
  default = null
}
