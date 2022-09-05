variable "deployment_location" {
  default = "us-west-2"
}
variable "deployment_name" {
  default = "no-deployment-name"
}
variable "debug" {
  default = false
}
variable "cors" {
    default = null
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
variable "logging" {
  default = {}
}
variable "files_expiry" {
    default = "P1D"
}
variable "exceptions" {
  default = {}
}
variable "email_sender" {
}
