output "endpoint" { value = aws_db_instance.this.address }
output "port" { value = aws_db_instance.this.port }
output "app_user_secret_arn" { value = aws_secretsmanager_secret.app_user.arn }
output "owner_secret_arn" { value = aws_secretsmanager_secret.owner.arn }
output "backfill_user_secret_arn" { value = aws_secretsmanager_secret.backfill_user.arn }
