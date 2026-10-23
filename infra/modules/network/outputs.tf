output "private_subnet_ids" { value = aws_subnet.private[*].id }
output "db_security_group_id" { value = aws_security_group.db.id }
output "app_security_group_id" { value = aws_security_group.app.id }
