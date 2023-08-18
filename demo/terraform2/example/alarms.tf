# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "emergencyInvocationsPerMinuteThreshold" {
    type = number
    default = 100
    nullable = false
}
variable "emergencyComputePerMinuteThreshold" {
    type = number
    default = 10000
    nullable = false
}
variable "panicInvocationsPerMinuteThreshold" {
    type = number
    default = 500
    nullable = false
}
variable "panicComputePerMinuteThreshold" {
    type = number
    default = 50000
    nullable = false
}
variable "emergencyContact" {
    type = string
    nullable = false
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

