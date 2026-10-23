# Customer managed key + alias. The KEY POLICY is the first gate, IAM policies are the second:
# KMS only allows an action if the key policy allows it, and the key policy here delegates day-to-day
# permissions to IAM (the "root" statement) while hard-limiting what no IAM policy can override.
data "aws_iam_policy_document" "key" {
  # 1. Delegate to IAM. Without this statement, IAM policies on the app role would have no effect.
  statement {
    sid       = "EnableIamPolicies"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${var.account_id}:root"]
    }
  }

  # 2. Admins manage the key but are explicitly denied every cryptographic use of it.
  statement {
    sid    = "AdminsCannotUseTheKey"
    effect = "Deny"
    actions = [
      "kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*",
    ]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = [var.key_admin_role_arn]
    }
  }

  # 3. Nobody may generate or unwrap data keys without an encryption context. The service always sends
  #    {tenant, vehicle}; this makes a context-less call (a copy/paste of ciphertext, a stolen role used
  #    ad hoc) fail even if IAM would allow it.
  dynamic "statement" {
    for_each = var.require_encryption_context ? [1] : []
    content {
      sid       = "RequireEncryptionContext"
      effect    = "Deny"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
      resources = ["*"]
      principals {
        type        = "AWS"
        identifiers = ["*"]
      }
      condition {
        test     = "Null"
        variable = "kms:EncryptionContextKeys"
        values   = ["true"]
      }
    }
  }
}

resource "aws_kms_key" "this" {
  description             = var.description
  enable_key_rotation     = true # new backing key material every year; old ciphertext still decrypts
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.key.json
  tags                    = var.tags
}

resource "aws_kms_alias" "this" {
  name          = var.alias
  target_key_id = aws_kms_key.this.key_id
}
