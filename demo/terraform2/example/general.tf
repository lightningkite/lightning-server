# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "cors" {
    type = object({ allowedDomains = list(string), allowedHeaders = list(string) })
    default = null
    nullable = true
}
variable "display_name" {
    type = string
    default = "demo-example"
    nullable = false
}

##########
# Outputs
##########


##########
# Resources
##########


