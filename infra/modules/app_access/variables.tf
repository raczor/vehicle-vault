variable "name" { type = string }
variable "app_role_name" { type = string }
variable "key_admin_role_name" { type = string }
variable "pii_key_arn" { type = string }
variable "secret_arns" {
  type    = list(string)
  default = []
}
variable "tags" {
  type    = map(string)
  default = {}
}
