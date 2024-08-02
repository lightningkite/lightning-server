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

resource "aws_cloudwatch_event_rule" "scheduled_task_clearOldMetrics" {
  name                = "demo-example_clearOldMetrics"
  schedule_expression = "rate(60 minutes)"
}
resource "aws_cloudwatch_event_target" "scheduled_task_clearOldMetrics" {
  rule      = aws_cloudwatch_event_rule.scheduled_task_clearOldMetrics.name
  target_id = "lambda"
  arn       = aws_lambda_alias.main.arn
  input     = "{\"scheduled\": \"clearOldMetrics\"}"
}
resource "aws_lambda_permission" "scheduled_task_clearOldMetrics" {
  action        = "lambda:InvokeFunction"
  function_name = "${aws_lambda_alias.main.function_name}:${aws_lambda_alias.main.name}"
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scheduled_task_clearOldMetrics.arn
  lifecycle {
    create_before_destroy = false
  }
}

