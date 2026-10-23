variable "alias" {
  type        = string
  description = "Must start with alias/"
}
variable "description" { type = string }
variable "account_id" { type = string }
variable "key_admin_role_arn" { type = string }
variable "require_encryption_context" {
  type    = bool
  default = true
}
variable "deletion_window_in_days" {
  type    = number
  default = 30
}
variable "tags" {
  type    = map(string)
  default = {}
}
