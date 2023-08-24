package com.lightningkite.lightningserver.azure

import com.lightningkite.lightningserver.auth.SecureHasher
import com.lightningkite.lightningserver.auth.SecureHasherSettings
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.email.EmailSettings
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.notifications.NotificationSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer



fun terraformAzure(projectName: String = "project", appendable: Appendable) {
    val namePrefix = "${projectName}_\${var.deployment_name}"
    val dependencies = ArrayList<String>()
    val appSettings = ArrayList<String>()
    appendable.appendLine("""
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
          name = "${namePrefix}_vault"
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
          name                     = "${namePrefix}_function_storage"
          resource_group_name      = azurerm_resource_group.resources.name
          location                 = azurerm_resource_group.resources.location
          account_tier             = "Standard"
          account_replication_type = "LRS"

          depends_on = [
            azurerm_resource_group.resources
          ]
        }
        
        resource "azurerm_service_plan" "service_plan" {
          name                = "${namePrefix}_service_plan"
          resource_group_name = azurerm_resource_group.resources.name
          location            = azurerm_resource_group.resources.location
          os_type             = "Linux"
          sku_name            = "Y1"
        
          depends_on = [
            azurerm_resource_group.resources
          ]
        }
        
        resource "azurerm_application_insights" "insights" {
          name                = "${namePrefix}_app_insights"
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

    """.trimIndent())
    dependencies.add("azurerm_resource_group.resources")
    dependencies.add("azurerm_storage_account.function_storage")
    dependencies.add("azurerm_application_insights.insights")
    dependencies.add("azurerm_key_vault.vault")
    for(setting in Settings.requirements) {
        when(setting.value.serializer) {
            serializer<FilesSettings>() -> {
                dependencies.add("azurerm_storage_account.${setting.key}_account")
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: FilesSettings
                    ####
                    
                    variable "${setting.key}_expiry" {
                        default = 86400
                    }
                    
                    resource "azurerm_storage_account" "${setting.key}_account" {
                      name                     = "${namePrefix}_${setting.key}_account"
                      resource_group_name      = azurerm_resource_group.resources.name
                      location                 = azurerm_resource_group.resources.location
                      account_tier             = "Standard"
                      account_replication_type = "LRS"

                      depends_on = [
                        azurerm_resource_group.resources
                      ]
                    }
                    
                    resource "azurerm_storage_container" "${setting.key}_container" {
                      name                  = "${namePrefix}_${setting.key}"
                      storage_account_name  = azurerm_storage_account.${setting.key}_account.name
                      container_access_type = "private"
                    }
                    
                    resource "azurerm_key_vault_secret" "${setting.key}_key" {
                      name = "${namePrefix}_${setting.key}_key"
                      value = azurerm_storage_account.${setting.key}_account.primary_access_key
                      key_vault_id = azurerm_key_vault.vault.id
                    
                      depends_on = [
                        azurerm_key_vault.vault,
                        azurerm_storage_account.${setting.key}_account
                      ]
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    storageUrl = azurerm_storage_account.${setting.key}_account.primary_file_endpoint
                    key = "@Microsoft.KeyVault(SecretUri=${'$'}{azurerm_key_vault.vault.vault_uri}secrets/${setting.key}_key)"
                    signedUrlExpirationSeconds = var.${setting.key}_expiry
                }""".trimIndent())
            }
            serializer<DatabaseSettings>() -> {
                dependencies.add("azurerm_cosmosdb_account.${setting.key}")
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: DatabaseSettings
                    ####
                    
                    variable "${setting.key}_databaseName" {
                        default = "$projectName"
                    }
                    resource "azurerm_cosmosdb_account" "${setting.key}" {
                      name = "${namePrefix}_${setting.key}"
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

                    resource "azurerm_key_vault_secret" "${setting.key}_key" {
                      name = "${namePrefix}_${setting.key}_key"
                      value = tostring(azurerm_cosmosdb_account.${setting.key}.connection_strings[0])
                      key_vault_id = azurerm_key_vault.vault.id

                      depends_on = [
                        azurerm_key_vault.vault,
                        azurerm_cosmosdb_account.${setting.key}
                      ]
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    url = "@Microsoft.KeyVault(SecretUri=${'$'}{azurerm_key_vault.vault.vault_uri}secrets/${setting.key}_key)"
                    databaseName = var.${setting.key}_databaseName
                }""".trimIndent())
            }
            serializer<CacheSettings>() -> {
                dependencies.add("azurerm_redis_cache.${setting.key}")
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: CacheSettings
                    ####
                    
                    variable "${setting.key}_cache_size" {
                      default = 0
                    }
                    variable "${setting.key}_databaseNumber" {
                      default = 0
                    }
                    
                    resource "azurerm_redis_cache" "${setting.key}" {
                      name = "${namePrefix}_${setting.key}"
                      location            = azurerm_resource_group.resources.location
                      resource_group_name = azurerm_resource_group.resources.name
                      capacity            = var.${setting.key}_cache_size
                      family              = "C"
                      sku_name            = "Standard"
                      enable_non_ssl_port = false
                      minimum_tls_version = "1.2"

                      redis_configuration {
                      }
                    }

                    resource "azurerm_key_vault_secret" "${setting.key}_primaryConnectionString" {
                      name = "${namePrefix}_${setting.key}_primaryConnectionString"
                      value = azurerm_redis_cache.${setting.key}.primary_connection_string
                      key_vault_id = azurerm_key_vault.vault.id

                      depends_on = [
                        azurerm_key_vault.vault,
                        azurerm_redis_cache.${setting.key}
                      ]
                    }
                    resource "azurerm_key_vault_secret" "${setting.key}_secondaryConnectionString" {
                      name = "${namePrefix}_${setting.key}_secondaryConnectionString"
                      value = azurerm_redis_cache.${setting.key}.secondary_connection_string
                      key_vault_id = azurerm_key_vault.vault.id

                      depends_on = [
                        azurerm_key_vault.vault,
                        azurerm_redis_cache.${setting.key}
                      ]
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    url = "redis://"
                    connectionString = "@Microsoft.KeyVault(SecretUri=${'$'}{azurerm_key_vault.vault.vault_uri}secrets/${setting.key}_primaryConnectionString)"
                    databaseNumber = var.${setting.key}_databaseNumber
                }""".trimIndent())
            }
            serializer<SecureHasherSettings>() -> {
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}: SecureHasherSettings
                    ####
                    resource "random_password" "${setting.key}" {
                      length           = 32
                      special          = true
                      override_special = "!#${'$'}%&*()-_=+[]{}<>:?"
                    }
                    resource "azurerm_key_vault_secret" "${setting.key}_key" {
                      name = "${namePrefix}_${setting.key}_key"
                      value = random_password.${setting.key}.result
                      key_vault_id = azurerm_key_vault.vault.id

                      depends_on = [
                        azurerm_key_vault.vault
                      ]
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = {
                    secret = "@Microsoft.KeyVault(SecretUri=${'$'}{azurerm_key_vault.vault.vault_uri}secrets/${setting.key}_key)"
                }""".trimIndent())
            }
            else -> {
                appendable.appendLine("""
                    
                    ####
                    # ${setting.key}
                    ####
                    
                    variable "${setting.key}" {
                      default = jsondecode("${setting.value.let { Serialization.json.encodeToString(it.serializer as KSerializer<Any?>, it.default).replace("\"", "\\\"") }}")
                    }
                """.trimIndent())
                appSettings.add("""${setting.key} = var.${setting.key}""".trimIndent())
            }
//            serializer<EmailSettings>() -> {}  // Azure has no email service, oddly enough
//            serializer<NotificationSettings>() -> {}  // Azure has no app notification service either
        }
    }

    // Now we create the outputs.
    appendable.appendLine("""
                    
        ####
        # Primary function app declaration.
        ####
                    
        resource "azurerm_linux_function_app" "main" {
          name                = "${namePrefix}_main"
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
                ${appSettings.joinToString(",\n                ")}
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

          depends_on = [${dependencies.joinToString()}]
        }
    """.trimIndent())
}