# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "jwt_expiration" {
    type = string
    default = "PT8760H"
    nullable = false
}
variable "jwt_emailExpiration" {
    type = string
    default = "PT1H"
    nullable = false
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

