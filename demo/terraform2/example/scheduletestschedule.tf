##########
# Inputs
##########


##########
# Outputs
##########


##########
# Resources
##########

resource "aws_cloudwatch_event_rule" "scheduled_task_testschedule" {
  name                = "demo-example_testschedule"
  schedule_expression = "rate(1 minute)"
}
resource "aws_cloudwatch_event_target" "scheduled_task_testschedule" {
  rule      = aws_cloudwatch_event_rule.scheduled_task_testschedule.name
  target_id = "lambda"
  arn       = aws_lambda_alias.main.arn
  input     = "{\"scheduled\": \"test-schedule\"}"
}
resource "aws_lambda_permission" "scheduled_task_testschedule" {
  statement_id  = "scheduled_task_testschedule"
  action        = "lambda:InvokeFunction"
  function_name = "${aws_lambda_alias.main.function_name}:${aws_lambda_alias.main.name}"
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scheduled_task_testschedule.arn
}

