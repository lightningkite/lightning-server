# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "database_min_capacity" {
    type = number
    default = 0.5
    nullable = false
}
variable "database_max_capacity" {
    type = number
    default = 2
    nullable = false
}
variable "database_auto_pause" {
    type = bool
    default = true
    nullable = false
}

##########
# Outputs
##########


##########
# Resources
##########

resource "random_password" "database" {
  length           = 32
  special          = true
  override_special = "-_"
}
resource "aws_rds_cluster" "database" {
  cluster_identifier = "demo-database"
  engine             = "aurora-postgresql"
  engine_mode        = "provisioned"
  engine_version     = "13.6"
  database_name      = "demodatabase"
  master_username = "master"
  master_password = random_password.database.result
  skip_final_snapshot = var.debug
  final_snapshot_identifier = "demo-database"
  
  

  serverlessv2_scaling_configuration {
    min_capacity = var.database_min_capacity
    max_capacity = var.database_max_capacity
  }
}

resource "aws_rds_cluster_instance" "database" {
  publicly_accessible = true
  cluster_identifier = aws_rds_cluster.database.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.database.engine
  engine_version     = aws_rds_cluster.database.engine_version
  
}

