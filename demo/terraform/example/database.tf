# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "database_org_id" {
  type     = string
  nullable = false
}
variable "database_zone_name" {
  type     = string
  nullable = true
}
variable "database_existing_project_id" {
  type        = string
  nullable    = true
  description = "An Existing Mongo Atlas Project you want this database added to (nullable). If null a new project will be created."
}

##########
# Outputs
##########


##########
# Resources
##########

resource "mongodbatlas_project" "database" {
  count  = var.database_existing_project_id == null ? 1 : 0
  name   = "demoexampledatabase"
  org_id = var.database_org_id

  is_collect_database_specifics_statistics_enabled = true
  is_data_explorer_enabled                         = true
  is_performance_advisor_enabled                   = true
  is_realtime_performance_panel_enabled            = true
  is_schema_advisor_enabled                        = true
}
resource "random_password" "database" {
  length           = 32
  special          = true
  override_special = "-_"
}
resource "mongodbatlas_advanced_cluster" "database" {
  project_id = (var.database_existing_project_id != null ? var.database_existing_project_id :
  mongodbatlas_project.database[0].id)
  name         = "demoexampledatabase"
  cluster_type = "REPLICASET"

  replication_specs {
    zone_name = var.database_zone_name
    region_configs {
      electable_specs {
        instance_size = "M0"
      }
      provider_name         = "TENANT"
      backing_provider_name = "AWS"
      region_name           = replace(upper(var.deployment_location), "-", "_")
      priority              = 7
    }
  }
}
resource "mongodbatlas_database_user" "database" {
  username = "demoexampledatabase-main"
  password = random_password.database.result
  project_id = (var.database_existing_project_id != null ? var.database_existing_project_id :
  mongodbatlas_project.database[0].id)
  auth_database_name = "admin"

  roles {
    role_name     = "readWrite"
    database_name = "default"
  }

  roles {
    role_name     = "readAnyDatabase"
    database_name = "admin"
  }

}
resource "mongodbatlas_project_ip_access_list" "database" {
  project_id = (var.database_existing_project_id != null ? var.database_existing_project_id :
  mongodbatlas_project.database[0].id)
  cidr_block = "0.0.0.0/0"
  comment    = "Anywhere"
}

