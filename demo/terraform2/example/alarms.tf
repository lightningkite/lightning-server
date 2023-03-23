# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "emergencyInvocationsPerMinuteThreshold" {
    type = number
    default = 100
}
variable "emergencyComputePerMinuteThreshold" {
    type = number
    default = 10000
}
variable "panicInvocationsPerMinuteThreshold" {
    type = number
    default = 500
}
variable "panicComputePerMinuteThreshold" {
    type = number
    default = 50000
}
variable "emergencyContact" {
    type = string
}

##########
# Outputs
##########


##########
# Resources
##########

resource "aws_sns_topic" "emergency" {
  name = "demo-example_emergencies"
}
resource "aws_sns_topic_subscription" "emergency_primary" {
  topic_arn = aws_sns_topic.emergency.arn
  protocol  = "email"
  endpoint  = var.emergencyContact
}
resource "aws_cloudwatch_metric_alarm" "emergency_invocations" {
  alarm_name                = "demo-example_emergency_invocations"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Invocations"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = "${var.emergencyInvocationsPerMinuteThreshold}"
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency.arn]
}
resource "aws_cloudwatch_metric_alarm" "emergency_compute" {
  alarm_name                = "demo-example_emergency_compute"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Duration"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = "${var.emergencyComputePerMinuteThreshold}"
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency.arn]
}
resource "aws_cloudwatch_metric_alarm" "panic_invocations" {
  alarm_name                = "demo-example_panic_invocations"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Invocations"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = "${var.panicInvocationsPerMinuteThreshold}"
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency.arn]
}
resource "aws_cloudwatch_metric_alarm" "panic_compute" {
  alarm_name                = "demo-example_panic_compute"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Duration"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = "${var.panicComputePerMinuteThreshold}"
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency.arn]
}
resource "aws_cloudwatch_event_rule" "panic" {
  name        = "demo-example_panic"
  description = "Throttle the function in a true emergency."
  event_pattern = jsonencode({
    source = ["aws.cloudwatch"]
    "detail-type" = ["CloudWatch Alarm State Change"]
    detail = {
      alarmName = [
        aws_cloudwatch_metric_alarm.panic_invocations.alarm_name, 
        aws_cloudwatch_metric_alarm.panic_compute.alarm_name
      ]
      previousState = {
        value = ["OK", "INSUFFICIENT_DATA"]
      }
      state = {
        value = ["ALARM"]
      }
    }
  })
}
resource "aws_cloudwatch_event_target" "panic" {
  rule      = aws_cloudwatch_event_rule.panic.name
  target_id = "lambda"
  arn       = aws_lambda_function.main.arn
  input     = "{\"panic\": true}"
  retry_policy {
    maximum_event_age_in_seconds = 60
    maximum_retry_attempts = 1
  }
}
resource "aws_lambda_permission" "panic" {
  action        = "lambda:InvokeFunction"
  function_name = "${aws_lambda_alias.main.function_name}:${aws_lambda_alias.main.name}"
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.panic.arn
  lifecycle {
    create_before_destroy = false
  }
}

resource "aws_iam_role_policy_attachment" "panic" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.panic.arn
}

resource "aws_iam_policy" "panic" {
  name        = "demo-example-panic"
  path = "/demo/example/panic/"
  description = "Access to self-throttle"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "lambda:PutFunctionConcurrency",
        ]
        Effect   = "Allow"
        Resource = [aws_lambda_function.main.arn]
      },
    ]
  })
}

