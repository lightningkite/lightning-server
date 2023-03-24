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

resource "aws_cloudwatch_event_rule" "scheduled_task_testschedule2" {
  name                = "demo-example_testschedule2"
  schedule_expression = "rate(1 minute)"
}
resource "aws_cloudwatch_event_target" "scheduled_task_testschedule2" {
  rule      = aws_cloudwatch_event_rule.scheduled_task_testschedule2.name
  target_id = "lambda"
  arn       = aws_lambda_alias.main.arn
  input     = "{\"scheduled\": \"test-schedule2\"}"
}
resource "aws_lambda_permission" "scheduled_task_testschedule2" {
  action        = "lambda:InvokeFunction"
  function_name = "${aws_lambda_alias.main.function_name}:${aws_lambda_alias.main.name}"
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scheduled_task_testschedule2.arn
  lifecycle {
    create_before_destroy = false
  }
}

