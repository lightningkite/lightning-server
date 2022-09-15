##########
# HTTP
##########
output "http_url" {
    value = module.Base.http_url
}
output "http" {
    value = module.Base.http
}

##########
# WebSockets
##########
output "ws_url" {
    value = module.Base.ws_url
}
output "ws" {
    value = module.Base.ws
}

