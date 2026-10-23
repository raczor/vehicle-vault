locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform {
  source = "${get_repo_root()}/infra/modules/kms"
}

dependency "iam" {
  config_path = "../iam"
  mock_outputs = {
    key_admin_role_arn = "arn:aws:iam::000000000000:role/mock-key-admin"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

inputs = {
  alias                      = "alias/${local.env.name}-rds"
  description                = "vehicle-vault RDS storage"
  account_id                 = local.env.account_id
  key_admin_role_arn         = dependency.iam.outputs.key_admin_role_arn
  require_encryption_context = false # RDS calls KMS itself and does not send our tenant/vehicle context
  deletion_window_in_days    = local.env.key_deletion_window_days
  tags                       = local.env.tags
}
