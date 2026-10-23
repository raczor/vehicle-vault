locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform {
  source = "${get_repo_root()}/infra/modules/app_access"
}

dependency "iam" {
  config_path = "../iam"
  mock_outputs = {
    app_role_name       = "mock-app"
    key_admin_role_name = "mock-key-admin"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

dependency "pii_key" {
  config_path = "../pii-key"
  mock_outputs = {
    key_arn = "arn:aws:kms:us-east-1:000000000000:key/mock"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

dependency "database" {
  config_path = "../database"
  mock_outputs = {
    app_user_secret_arn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:mock"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

inputs = {
  name                = local.env.name
  app_role_name       = dependency.iam.outputs.app_role_name
  key_admin_role_name = dependency.iam.outputs.key_admin_role_name
  pii_key_arn         = dependency.pii_key.outputs.key_arn
  secret_arns         = [dependency.database.outputs.app_user_secret_arn]
  tags                = local.env.tags
}
