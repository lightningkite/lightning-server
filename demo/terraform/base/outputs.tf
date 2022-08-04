output "http_url" {
  value = aws_apigatewayv2_stage.http.invoke_url
}
output "ws_url" {
  value = aws_apigatewayv2_stage.ws.invoke_url
}
