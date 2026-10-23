variable "name" { type = string }
variable "subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }
variable "storage_key_arn" { type = string }
variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}
variable "backup_retention_days" {
  type    = number
  default = 7
}
variable "deletion_protection" {
  type    = bool
  default = true
}
variable "secret_recovery_window_days" {
  type        = number
  default     = 30
  description = "0 deletes secrets immediately and permanently; keep >= 7 outside throwaway environments"
}
variable "tags" {
  type    = map(string)
  default = {}
}
