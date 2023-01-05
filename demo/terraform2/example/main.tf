terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      version = "~> 4.30"
    }
    local = {
      source = "hashicorp/local"
      version = "~> 2.2"
    }
    random = {
      source = "hashicorp/random"
      version = "~> 3.1.0"
    }
    archive = {
      source = "hashicorp/archive"
      version = "~> 2.2.0"
    }
    mongodbatlas = {
      source = "mongodb/mongodbatlas"
      version = "~> 1.4"
    }
  }
  backend "s3" {
    bucket = "ivieleague-deployment-states"
    key    = "demo/example"
    region = "us-west-2"
  }
}
provider "aws" {
  region = "us-west-2"
}
provider "aws" {
  alias = "acm"
  region = "us-east-1"
}
provider "mongodbatlas" {
}
