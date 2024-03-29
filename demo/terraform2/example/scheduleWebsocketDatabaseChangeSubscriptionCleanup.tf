# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########


##########
# Outputs
##########


##########
# Resources
##########

resource "aws_cloudwatch_event_rule" "scheduled_task_WebsocketDatabaseChangeSubscriptionCleanup" {
  name                = "demo-example_WebsocketDatabaseChangeSubscriptionCleanup"
  schedule_expression = "rate(5 minutes)"
}
resource "aws_cloudwatch_event_target" "scheduled_task_WebsocketDatabaseChangeSubscriptionCleanup" {
  rule      = aws_cloudwatch_event_rule.scheduled_task_WebsocketDatabaseChangeSubscriptionCleanup.name
  target_id = "lambda"
  arn       = aws_lambda_alias.main.arn
  input     = "{\"scheduled\": \"WebsocketDatabaseChangeSubscriptionCleanup\"}"
}
resource "aws_lambda_permission" "scheduled_task_WebsocketDatabaseChangeSubscriptionCleanup" {
  action        = "lambda:InvokeFunction"
  function_name = "${aws_lambda_alias.main.function_name}:${aws_lambda_alias.main.name}"
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scheduled_task_WebsocketDatabaseChangeSubscriptionCleanup.arn
  lifecycle {
    create_before_destroy = false
  }
}

