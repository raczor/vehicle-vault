# Minimal private network for the database: one VPC, two private subnets (RDS requires 2 AZs), and a
# security group that only lets the app's security group reach Postgres.
resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = var.name })
}

resource "aws_subnet" "private" {
  count             = length(var.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.cidr, 8, count.index)
  tags              = merge(var.tags, { Name = "${var.name}-private-${count.index}" })
}

resource "aws_security_group" "app" {
  name        = "${var.name}-app"
  description = "Attach to the service; the only source allowed to reach the database"
  vpc_id      = aws_vpc.this.id
  tags        = var.tags
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "Postgres, reachable only from the app security group"
  vpc_id      = aws_vpc.this.id
  tags        = var.tags
}

# Security groups created by Terraform have no egress at all (unlike the console), so the app group must be
# given the one outbound path it needs. Calls to KMS and Secrets Manager (443) need their own egress or
# VPC endpoints, which belong to the service's networking and are not created here.
resource "aws_vpc_security_group_egress_rule" "app_to_db" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
