# Two roles with deliberately separate powers (separation of duties):
#   app       - what the running service assumes: can USE the key, cannot manage it.
#   key_admin - what a human/pipeline assumes to MANAGE the key: cannot encrypt or decrypt anything.
data "aws_iam_policy_document" "assume_app" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "assume_admin" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${var.account_id}:root"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.name}-app"
  assume_role_policy = data.aws_iam_policy_document.assume_app.json
  tags               = var.tags
}

resource "aws_iam_role" "key_admin" {
  name               = "${var.name}-key-admin"
  assume_role_policy = data.aws_iam_policy_document.assume_admin.json
  tags               = var.tags
}
