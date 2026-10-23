# Least-privilege IAM policy for the service role. Second gate after the key policy.
data "aws_iam_policy_document" "app" {
  # Only the two calls the service makes (envelope encryption), only on this key, and only when the
  # request carries BOTH encryption-context keys the code sends. No Encrypt, no CreateKey, no admin calls.
  statement {
    sid       = "UsePiiKey"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [var.pii_key_arn]
    condition {
      test     = "ForAllValues:StringEquals"
      variable = "kms:EncryptionContextKeys"
      values   = ["tenant", "vehicle"]
    }
    condition {
      test     = "Null"
      variable = "kms:EncryptionContextKeys"
      values   = ["false"]
    }
  }

  dynamic "statement" {
    for_each = length(var.secret_arns) > 0 ? [1] : []
    content {
      sid       = "ReadOwnSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = var.secret_arns
    }
  }
}

resource "aws_iam_policy" "app" {
  name   = "${var.name}-app"
  policy = data.aws_iam_policy_document.app.json
  tags   = var.tags
}

resource "aws_iam_role_policy_attachment" "app" {
  role       = var.app_role_name
  policy_arn = aws_iam_policy.app.arn
}

# Key admins: manage the key, never use it (the key policy also hard-denies cryptographic use).
data "aws_iam_policy_document" "admin" {
  statement {
    sid = "ManagePiiKey"
    actions = [
      "kms:Describe*", "kms:Get*", "kms:List*", "kms:Enable*", "kms:Disable*", "kms:Put*", "kms:Update*",
      "kms:TagResource", "kms:UntagResource", "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion",
    ]
    resources = [var.pii_key_arn]
  }
}

resource "aws_iam_role_policy" "admin" {
  name   = "${var.name}-key-admin"
  role   = var.key_admin_role_name
  policy = data.aws_iam_policy_document.admin.json
}
