locals {
  env  = "prod"
  name = "vehicle-vault"
  # Required, no default: a wrong account id would build the key policy for the wrong account.
  # CI sets a placeholder only for `validate`.
  account_id = get_env("AWS_ACCOUNT_ID")
  region     = get_env("AWS_REGION", "us-east-1")
  azs        = ["${get_env("AWS_REGION", "us-east-1")}a", "${get_env("AWS_REGION", "us-east-1")}b"]
  tags       = { Project = "vehicle-vault", Env = "prod" }

  key_deletion_window_days = 30
  db_deletion_protection   = true
  db_backup_retention_days = 7
  secret_recovery_days     = 30

  provider_hcl = <<-EOT
    provider "aws" {
      region = "${get_env("AWS_REGION", "us-east-1")}"
    }
  EOT
}
