variable "name" { type = string }
variable "cidr" {
  type    = string
  default = "10.20.0.0/16"
}
variable "azs" { type = list(string) }
variable "tags" {
  type    = map(string)
  default = {}
}
