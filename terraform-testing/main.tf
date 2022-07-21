terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.1.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.2.0"
    }
  }

  required_version = "~> 1.0"
}

provider "aws" {
  region = var.deployment_location
}

variable "deployment_location" {
  default = "us-west-2"
}

module "vpc" {
  source = "terraform-aws-modules/vpc/aws"

  name = "terraform-testing"
  cidr = "10.0.0.0/16"

  azs             = ["us-west-2a", "us-west-2b", "us-west-2c"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  enable_nat_gateway = false
  enable_vpn_gateway = false

  tags = {
    Terraform   = "true"
    Environment = "dev"
  }
}

resource "aws_security_group" "database" {
  name   = "terraform-testing-service"
  vpc_id = "${module.vpc.vpc_id}"

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = module.vpc.private_subnets_cidr_blocks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = module.vpc.private_subnets_cidr_blocks
  }
}

variable "database_expiry" {
  default = "P1D"
}
resource "random_password" "database" {
  length           = 32
  special          = true
  override_special = "-_"
}
resource "aws_docdb_subnet_group" "database" {
  name       = "terraform-testing-database"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_docdb_cluster_instance" "database" {
  count              = 1
  identifier         = "terraform-testing-database-${count.index}"
  cluster_identifier = "${aws_docdb_cluster.database.id}"
  instance_class     = "db.t4g.medium"
}

resource "aws_docdb_cluster_parameter_group" "database" {
  family = "docdb4.0"
  name = "terraform-testing-database-parameter-group"
  parameter {
    name  = "tls"
    value = "disabled"
  }
}
resource "aws_docdb_cluster" "database" {
  cluster_identifier      = "terraform-testing-database"
  engine                  = "docdb"
  master_username         = "terraformtesting"
  master_password                = random_password.database.result
  backup_retention_period = 5
  preferred_backup_window = "07:00-09:00"
  skip_final_snapshot     = true

  db_cluster_parameter_group_name = "${aws_docdb_cluster_parameter_group.database.name}"
  vpc_security_group_ids = ["${aws_security_group.database.id}"]
  db_subnet_group_name    = "${aws_docdb_subnet_group.database.name}"
}