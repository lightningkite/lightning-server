# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########


##########
# Outputs
##########


##########
# Resources
##########

resource "random_password" "secretBasis" {
  length           = 88
  special          = true
  override_special = "+/"
}

