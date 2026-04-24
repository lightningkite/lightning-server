# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "cors" {
  type = object({
    allowedDomains   = optional(list(string), null), # Removing in V5
    allowedHeaders   = optional(list(string), null), # Removing in V5
    limitToDomains   = list(string),                 # Nullable
    limitToHeaders   = list(string),                 # Nullable
    limitToMethods   = list(string),                 # Nullable
    exposedHeaders   = list(string),
    allowCredentials = bool,
  })
  default     = null
  nullable    = true
  description = "Defines the cors rules for the server."
}
variable "display_name" {
  type        = string
  default     = "demo-example"
  nullable    = false
  description = "The GeneralSettings projectName."
}

##########
# Outputs
##########


##########
# Resources
##########


