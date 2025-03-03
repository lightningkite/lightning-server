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

resource "aws_cloudwatch_event_rule" "scheduled_task_applicationHealthCheckautomaticallyCreate" {
  name                = "demo-example_applicationHealthCheckautomaticallyCreate"
  schedule_expression = "rate(1 minute)"
}
resource "aws_cloudwatch_event_target" "scheduled_task_applicationHealthCheckautomaticallyCreate" {
  rule      = aws_cloudwatch_event_rule.scheduled_task_applicationHealthCheckautomaticallyCreate.name
  target_id = "lambda"
  arn       = aws_lambda_alias.main.arn
  input     = "{\"scheduled\": \"/applicationHealthCheck/automaticallyCreate\"}"
}

