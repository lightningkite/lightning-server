####
# General configuration for an Azure functions project
####

terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0.2"
    }
  }

  required_version = ">= 1.1.0"
}

provider "azurerm" {
  features {}
}

variable "deployment_location" {
  default = "westus"
}
variable "deployment_name" {
  default = "test"
}

data "azurerm_client_config" "current" {}

resource "azurerm_resource_group" "resources" {
  name     = var.deployment_name
  location = var.deployment_location
}

resource "azurerm_key_vault" "vault" {
  name = "demo_${var.deployment_name}_vault"
  resource_group_name = azurerm_resource_group.resources.name
  location = azurerm_resource_group.resources.location
  tenant_id = data.azurerm_client_config.current.tenant_id
  sku_name = "standard"

  enable_rbac_authorization = true

  depends_on = [
    azurerm_resource_group.resources
  ]
}

resource "azurerm_storage_account" "function_storage" {
  name                     = "demo_${var.deployment_name}_function_storage"
  resource_group_name      = azurerm_resource_group.resources.name
  location                 = azurerm_resource_group.resources.location
  account_tier             = "Standard"
  account_replication_type = "LRS"

  depends_on = [
    azurerm_resource_group.resources
  ]
}

resource "azurerm_service_plan" "service_plan" {
  name                = "demo_${var.deployment_name}_service_plan"
  resource_group_name = azurerm_resource_group.resources.name
  location            = azurerm_resource_group.resources.location
  os_type             = "Linux"
  sku_name            = "Y1"

  depends_on = [
    azurerm_resource_group.resources
  ]
}

resource "azurerm_application_insights" "insights" {
  name                = "demo_${var.deployment_name}_app_insights"
  location            = azurerm_resource_group.resources.location
  resource_group_name = azurerm_resource_group.resources.name
  application_type    = "java"

  depends_on = [
    azurerm_resource_group.resources
  ]
}


###################
# RBAC Assignment #
###################
resource "azurerm_role_assignment" "keyvault_secrets_access" {
  role_definition_name = "Key Vault Secrets User"
  scope                = azurerm_resource_group.resources.id
  principal_id         = azurerm_linux_function_app.main.identity[0].principal_id

  depends_on = [
    azurerm_linux_function_app.main,
  ]
}

resource "azurerm_role_assignment" "keyvault_crypto_access" {
  role_definition_name = "Key Vault Crypto User"
  scope                = azurerm_resource_group.resources.id
  principal_id         = azurerm_linux_function_app.main.identity[0].principal_id

  depends_on = [
    azurerm_linux_function_app.main,
  ]
}


####
# general: JwtSigner
####

variable "general" {
  default = jsondecode("{}")
}

####
# database: DatabaseSettings
####

variable "database_databaseName" {
  default = "demo"
}
resource "azurerm_cosmosdb_account" "database" {
  name = "demo_${var.deployment_name}_database"
  location = azurerm_resource_group.resources.location
  resource_group_name = azurerm_resource_group.resources.name
  offer_type          = "Standard"
  kind                = "MongoDB"
  consistency_policy {
    consistency_level = "BoundedStaleness"
  }
  geo_location {
    location          = azurerm_resource_group.resources.location
    failover_priority = 0
  }
  capabilities {
    name = "mongoEnableDocLevelTTL"
  }

  capabilities {
    name = "MongoDBv3.4"
  }

  capabilities {
    name = "EnableMongo"
  }

  depends_on = [
    azurerm_resource_group.resources
  ]
}

resource "azurerm_key_vault_secret" "database_key" {
  name = "demo_${var.deployment_name}_database_key"
  value = tostring(azurerm_cosmosdb_account.database.connection_strings[0])
  key_vault_id = azurerm_key_vault.vault.id

  depends_on = [
    azurerm_key_vault.vault,
    azurerm_cosmosdb_account.database
  ]
}

####
# jwt: JwtSigner
####

variable "jwt_expirationMilliseconds" {
  default = 31540000000
}
variable "jwt_emailExpirationMilliseconds" {
  default = 1800000
}
resource "random_password" "jwt" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}
resource "azurerm_key_vault_secret" "jwt_key" {
  name = "demo_${var.deployment_name}_jwt_key"
  value = random_password.jwt.result
  key_vault_id = azurerm_key_vault.vault.id

  depends_on = [
    azurerm_key_vault.vault
  ]
}

####
# oauth-google: JwtSigner
####

variable "oauth-google" {
  default = jsondecode("null")
}

####
# logging: JwtSigner
####

variable "logging" {
  default = jsondecode("{}")
}

####
# files: FilesSettings
####

variable "files_expiry" {
  default = 86400
}

resource "azurerm_storage_account" "files_account" {
  name                     = "demo_${var.deployment_name}_files_account"
  resource_group_name      = azurerm_resource_group.resources.name
  location                 = azurerm_resource_group.resources.location
  account_tier             = "Standard"
  account_replication_type = "LRS"

  depends_on = [
    azurerm_resource_group.resources
  ]
}

resource "azurerm_storage_container" "files_container" {
  name                  = "demo_${var.deployment_name}_files"
  storage_account_name  = azurerm_storage_account.files_account.name
  container_access_type = "private"
}

resource "azurerm_key_vault_secret" "files_key" {
  name = "demo_${var.deployment_name}_files_key"
  value = azurerm_storage_account.files_account.primary_access_key
  key_vault_id = azurerm_key_vault.vault.id

  depends_on = [
    azurerm_key_vault.vault,
    azurerm_storage_account.files_account
  ]
}

####
# oauth-github: JwtSigner
####

variable "oauth-github" {
  default = jsondecode("null")
}

####
# exceptions: JwtSigner
####

variable "exceptions" {
  default = jsondecode("{}")
}

####
# email: JwtSigner
####

variable "email" {
  default = jsondecode("{}")
}

####
# oauth-apple: JwtSigner
####

variable "oauth-apple" {
  default = jsondecode("null")
}

####
# Primary function app declaration.
####

resource "azurerm_linux_function_app" "main" {
  name                = "demo_${var.deployment_name}_main"
  resource_group_name = azurerm_resource_group.resources.name
  location            = azurerm_resource_group.resources.location

  storage_account_name       = azurerm_storage_account.function_storage.name
  storage_account_access_key = azurerm_storage_account.function_storage.primary_access_key

  service_plan_id = azurerm_service_plan.service_plan.id

  app_settings = {
    "FUNCTIONS_EXTENSION_VERSION"    = "~3"
    "FUNCTIONS_WORKER_RUNTIME"       = "java"
    "FUNCTIONS_WORKER_PROCESS_COUNT" = "10"
    "WEBSITE_RUN_FROM_PACKAGE"       = "1"
    "APPINSIGHTS_INSTRUMENTATIONKEY" = azurerm_application_insights.insights.instrumentation_key
    "AzureWebJobsStorage"            = azurerm_storage_account.function_storage.primary_connection_string

    # ENV Variables
    "APP_SETTINGS" = jsonencode({
      general = var.general,
      database = {
        url = "@Microsoft.KeyVault(SecretUri=${azurerm_key_vault.vault.vault_uri}secrets/database_key)"
        databaseName = var.database_databaseName
      },
      jwt = {
        expirationMilliseconds = var.jwt_expirationMilliseconds
        emailExpirationMilliseconds = var.jwt_emailExpirationMilliseconds
        secret = "@Microsoft.KeyVault(SecretUri=${azurerm_key_vault.vault.vault_uri}secrets/jwt_key)"
      },
      oauth-google = var.oauth-google,
      logging = var.logging,
      files = {
        storageUrl = azurerm_storage_account.files_account.primary_file_endpoint
        key = "@Microsoft.KeyVault(SecretUri=${azurerm_key_vault.vault.vault_uri}secrets/files_key)"
        signedUrlExpirationSeconds = var.files_expiry
      },
      oauth-github = var.oauth-github,
      exceptions = var.exceptions,
      email = var.email,
      oauth-apple = var.oauth-apple
    })
    "KEY_VAULT_TOKEN_KEY_NAME"  = "token-signing-key"
    "KEY_VAULT_URI"             = azurerm_key_vault.vault.vault_uri
  }
  identity {
    type = "SystemAssigned"
  }
  site_config {
    http2_enabled = true
  }

  depends_on = [azurerm_resource_group.resources, azurerm_storage_account.function_storage, azurerm_application_insights.insights, azurerm_key_vault.vault, azurerm_cosmosdb_account.database, azurerm_storage_account.files_account]
}
