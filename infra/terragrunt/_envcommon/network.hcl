locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform {
  source = "${get_repo_root()}/infra/modules/network"
}

inputs = {
  name = local.env.name
  azs  = local.env.azs
  tags = local.env.tags
}
