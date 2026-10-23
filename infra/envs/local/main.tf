terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

# floci answers the AWS API on localhost:4566. Credentials are dummies; account checks are skipped.
provider "aws" {
  region                      = "us-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  s3_use_path_style           = true
  endpoints {
    kms            = "http://localhost:4566"
    iam            = "http://localhost:4566"
    sts            = "http://localhost:4566"
    secretsmanager = "http://localhost:4566"
    rds            = "http://localhost:4566"
    ec2            = "http://localhost:4566"
  }
}

locals {
  name       = "vehicle-vault"
  account_id = "000000000000" # floci's default account
  tags       = { Project = "vehicle-vault", Env = "local" }
}

module "iam" {
  source     = "../../modules/iam"
  name       = local.name
  account_id = local.account_id
  tags       = local.tags
}

module "pii_key" {
  source                  = "../../modules/kms"
  alias                   = "alias/vehicle-vault-pii"
  description             = "vehicle-vault PII envelope encryption"
  account_id              = local.account_id
  key_admin_role_arn      = module.iam.key_admin_role_arn
  deletion_window_in_days = 7
  tags                    = local.tags
}

# Second key, only for RDS storage encryption. Separate from the PII key so a database snapshot copied to
# another account cannot be read without THIS key, and the PII key's blast radius stays at field level.
module "rds_key" {
  source                     = "../../modules/kms"
  alias                      = "alias/vehicle-vault-rds"
  description                = "vehicle-vault RDS storage"
  account_id                 = local.account_id
  key_admin_role_arn         = module.iam.key_admin_role_arn
  require_encryption_context = false # RDS calls KMS itself and does not send our tenant/vehicle context
  deletion_window_in_days    = 7
  tags                       = local.tags
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  azs    = ["us-east-1a", "us-east-1b"]
  tags   = local.tags
}

module "database" {
  source                      = "../../modules/database"
  name                        = local.name
  subnet_ids                  = module.network.private_subnet_ids
  security_group_id           = module.network.db_security_group_id
  storage_key_arn             = module.rds_key.key_arn
  backup_retention_days       = 1
  deletion_protection         = false
  secret_recovery_window_days = 0
  tags                        = local.tags
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
output "pii_key_alias" { value = module.pii_key.alias_name }
output "app_role_arn" { value = module.iam.app_role_arn }
output "db_endpoint" { value = module.database.endpoint }
output "db_port" { value = module.database.port }
