# Real AWS. Same modules as envs/local; only the provider and the safety dials change.
# This environment is validated in CI (`tofu validate`) but never applied by this repo's automation.
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Remote state is a must for real use (state holds the generated DB passwords). Example:
  # backend "s3" { bucket = "...", key = "vehicle-vault/terraform.tfstate", region = "...", encrypt = true, kms_key_id = "..." }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name       = "vehicle-vault"
  account_id = data.aws_caller_identity.current.account_id
  tags       = { Project = "vehicle-vault", Env = "prod" }
}

module "iam" {
  source     = "../../modules/iam"
  name       = local.name
  account_id = local.account_id
  tags       = local.tags
}

module "pii_key" {
  source             = "../../modules/kms"
  alias              = "alias/vehicle-vault-pii"
  description        = "vehicle-vault PII envelope encryption"
  account_id         = local.account_id
  key_admin_role_arn = module.iam.key_admin_role_arn
  tags               = local.tags # 30 day deletion window and encryption-context enforcement are the defaults
}

module "rds_key" {
  source                     = "../../modules/kms"
  alias                      = "alias/vehicle-vault-rds"
  description                = "vehicle-vault RDS storage"
  account_id                 = local.account_id
  key_admin_role_arn         = module.iam.key_admin_role_arn
  require_encryption_context = false
  tags                       = local.tags
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  azs    = slice(data.aws_availability_zones.available.names, 0, 2)
  tags   = local.tags
}

module "database" {
  source            = "../../modules/database"
  name              = local.name
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.db_security_group_id
  storage_key_arn   = module.rds_key.key_arn
  tags              = local.tags # deletion protection on, 7 day backups: the defaults
}

module "app_access" {
  source              = "../../modules/app_access"
  name                = local.name
  app_role_name       = module.iam.app_role_name
  key_admin_role_name = module.iam.key_admin_role_name
  pii_key_arn         = module.pii_key.key_arn
  secret_arns         = [module.database.app_user_secret_arn]
  tags                = local.tags
}

output "pii_key_arn" { value = module.pii_key.key_arn }
output "app_role_arn" { value = module.iam.app_role_arn }
output "db_endpoint" { value = module.database.endpoint }
