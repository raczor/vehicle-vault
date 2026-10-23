# Included by every leaf. Defines, once, what plain Terraform repeats per environment:
# the OpenTofu binary, the provider block, the provider versions and where state lives.
locals {
  env = read_terragrunt_config(find_in_parent_folders("env.hcl")).locals
}

terraform_binary = "tofu"

# Each leaf gets its own generated provider.tf. The content differs per environment (floci endpoints vs real AWS)
# and comes from that environment's env.hcl.
generate "provider" {
  path      = "provider.tf"
  if_exists = "overwrite_terragrunt"
  contents  = local.env.provider_hcl
}

generate "versions" {
  path      = "versions.tf"
  if_exists = "overwrite_terragrunt"
  contents  = <<-EOT
    terraform {
      required_version = ">= 1.6"
      required_providers {
        aws    = { source = "hashicorp/aws", version = "~> 5.0" }
        random = { source = "hashicorp/random", version = "~> 3.6" }
      }
    }
  EOT
}

# One state file per leaf (per component), next to its terragrunt.hcl. Local files here because the state of the
# floci environment is disposable. For real AWS swap this for backend "s3" (encrypted, locked): the state holds
# the generated DB passwords.
remote_state {
  backend = "local"
  generate = {
    path      = "backend.tf"
    if_exists = "overwrite_terragrunt"
  }
  config = {
    path = "${get_terragrunt_dir()}/terraform.tfstate"
  }
}
