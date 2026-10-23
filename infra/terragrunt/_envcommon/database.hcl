locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform {
  source = "${get_repo_root()}/infra/modules/database"
}

dependency "network" {
  config_path = "../network"
  mock_outputs = {
    private_subnet_ids   = ["subnet-mock1", "subnet-mock2"]
    db_security_group_id = "sg-mock"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

dependency "rds_key" {
  config_path = "../rds-key"
  mock_outputs = {
    key_arn = "arn:aws:kms:us-east-1:000000000000:key/mock"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

inputs = {
  name                        = local.env.name
  subnet_ids                  = dependency.network.outputs.private_subnet_ids
  security_group_id           = dependency.network.outputs.db_security_group_id
  storage_key_arn             = dependency.rds_key.outputs.key_arn
  backup_retention_days       = local.env.db_backup_retention_days
  deletion_protection         = local.env.db_deletion_protection
  secret_recovery_window_days = local.env.secret_recovery_days
  tags                        = local.env.tags
}
