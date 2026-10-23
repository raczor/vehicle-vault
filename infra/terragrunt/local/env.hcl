# Everything that differs between environments lives in env.hcl.
locals {
  env        = "local"
  name       = "vehicle-vault"
  account_id = "000000000000" # floci's default account
  region     = "us-east-1"
  azs        = ["us-east-1a", "us-east-1b"]
  tags       = { Project = "vehicle-vault", Env = "local" }

  # Safety dials: throwaway environment, so short windows and no protection.
  key_deletion_window_days = 7
  db_deletion_protection   = false
  db_backup_retention_days = 1
  secret_recovery_days     = 0

  provider_hcl = <<-EOT
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
  EOT
}
