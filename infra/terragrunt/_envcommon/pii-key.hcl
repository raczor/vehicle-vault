locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform {
  source = "${get_repo_root()}/infra/modules/kms"
}

# Needs the key-admin role's ARN for the key policy, so the iam leaf must be applied first.
dependency "iam" {
  config_path = "../iam"
  mock_outputs = {
    key_admin_role_arn = "arn:aws:iam::000000000000:role/mock-key-admin"
  }
  mock_outputs_allowed_terraform_commands = ["validate", "plan"]
}

inputs = {
  alias                   = "alias/${local.env.name}-pii"
  description             = "vehicle-vault PII envelope encryption"
  account_id              = local.env.account_id
  key_admin_role_arn      = dependency.iam.outputs.key_admin_role_arn
  deletion_window_in_days = local.env.key_deletion_window_days
  tags                    = local.env.tags
}
