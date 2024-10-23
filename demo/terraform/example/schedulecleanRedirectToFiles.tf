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

resource "aws_cloudwatch_event_rule" "scheduled_task_cleanRedirectToFiles" {
  name                = "demo-example_cleanRedirectToFiles"
  schedule_expression = "rate(1440 minutes)"
}
resource "aws_cloudwatch_event_target" "scheduled_task_cleanRedirectToFiles" {
  rule      = aws_cloudwatch_event_rule.scheduled_task_cleanRedirectToFiles.name
  target_id = "lambda"
  arn       = aws_lambda_alias.main.arn
  input     = "{\"scheduled\": \"cleanRedirectToFiles\"}"
}

