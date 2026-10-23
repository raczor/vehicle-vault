# Postgres on RDS. Terraform creates the INSTANCE and the credentials; the TABLES come from Flyway
# (src/main/resources/db/migration) when the app starts, and the roles app_user / backfill_user from
# docker/initdb-roles.sql. Terraform owns infrastructure, migrations own schema: never mix the two.

resource "random_password" "owner" {
  length  = 32
  special = false
}

resource "random_password" "app_user" {
  length  = 32
  special = false
}

resource "random_password" "backfill_user" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_db_parameter_group" "this" {
  name   = "${var.name}-pg17"
  family = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1" # reject non-TLS connections
  }
  parameter {
    name  = "log_statement"
    value = "ddl" # audit schema changes without logging row data (PII)
  }
  tags = var.tags
}

resource "aws_db_instance" "this" {
  identifier             = var.name
  engine                 = "postgres"
  engine_version         = "17"
  instance_class         = var.instance_class
  allocated_storage      = 20
  db_name                = "vault"
  username               = "vault_owner"
  password               = random_password.owner.result
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  parameter_group_name   = aws_db_parameter_group.this.name

  storage_encrypted       = true
  kms_key_id              = var.storage_key_arn # data at rest, separate from the PII field-level key
  publicly_accessible     = false
  backup_retention_period = var.backup_retention_days
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = !var.deletion_protection
  apply_immediately       = true
  tags                    = var.tags
}

# One secret per credential so IAM can grant the service only what it needs:
# the app reads app_user; only the migration/ops path reads owner; only the one-off job reads backfill_user.
resource "aws_secretsmanager_secret" "owner" {
  name                    = "${var.name}/db/owner"
  recovery_window_in_days = var.secret_recovery_window_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret" "app_user" {
  name                    = "${var.name}/db/app_user"
  recovery_window_in_days = var.secret_recovery_window_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret" "backfill_user" {
  name                    = "${var.name}/db/backfill_user"
  recovery_window_in_days = var.secret_recovery_window_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "owner" {
  secret_id = aws_secretsmanager_secret.owner.id
  secret_string = jsonencode({
    username = "vault_owner", password = random_password.owner.result,
    host     = aws_db_instance.this.address, port = aws_db_instance.this.port, dbname = "vault"
  })
}

resource "aws_secretsmanager_secret_version" "app_user" {
  secret_id = aws_secretsmanager_secret.app_user.id
  secret_string = jsonencode({
    username = "app_user", password = random_password.app_user.result,
    host     = aws_db_instance.this.address, port = aws_db_instance.this.port, dbname = "vault"
  })
}

resource "aws_secretsmanager_secret_version" "backfill_user" {
  secret_id = aws_secretsmanager_secret.backfill_user.id
  secret_string = jsonencode({
    username = "backfill_user", password = random_password.backfill_user.result,
    host     = aws_db_instance.this.address, port = aws_db_instance.this.port, dbname = "vault"
  })
}
