##########
# HTTP
##########
output "http_url" {
    value = aws_apigatewayv2_stage.http.invoke_url
}
output "http" {
    value = {
    id = aws_apigatewayv2_stage.http.id
    api_id = aws_apigatewayv2_stage.http.api_id
    invoke_url = aws_apigatewayv2_stage.http.invoke_url
    arn = aws_apigatewayv2_stage.http.arn
    name = aws_apigatewayv2_stage.http.name
}
}

##########
# WebSockets
##########
output "ws_url" {
    value = aws_apigatewayv2_stage.ws.invoke_url
}
output "ws" {
    value = {
    id = aws_apigatewayv2_stage.ws.id
    api_id = aws_apigatewayv2_stage.ws.api_id
    invoke_url = aws_apigatewayv2_stage.ws.invoke_url
    arn = aws_apigatewayv2_stage.ws.arn
    name = aws_apigatewayv2_stage.ws.name
}
}

