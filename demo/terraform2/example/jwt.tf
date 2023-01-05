##########
# Inputs
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
# Outputs
##########


##########
# Resources
##########

resource "random_password" "jwt" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

