locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform {
  source = "${get_repo_root()}/infra/modules/iam"
}

inputs = {
  name       = local.env.name
  account_id = local.env.account_id
  tags       = local.env.tags
}
