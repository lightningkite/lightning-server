# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "emergencyInvocationsPerMinuteThreshold" {
    type = number
    default = null
    nullable = true
    description = "Number of Invocations Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use emergencyInvocations which allows defined both threshold and period)"
}
variable "emergencyComputePerMinuteThreshold" {
    type = number
    default = null
    nullable = true
    description = "Milliseconds of Compute Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use emergencyCompute which allows defined both threshold and period)"
}
variable "panicInvocationsPerMinuteThreshold" {
    type = number
    default = null
    nullable = true
    description = "Number of Invocations Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use panicInvocations which allows defined both threshold and period)"
}
variable "panicComputePerMinuteThreshold" {
    type = number
    default = null
    nullable = true
    description = "Milliseconds of Compute Per Minute, Assign null to not create this alarm. (DEPRECATED!! Use panicCompute which allows defined both threshold and period)"
}
variable "emergencyInvocations" {
    type = object({ threshold = number, period = number, evaluationPeriods = number, dataPointsToAlarm = number })
    default = null
    nullable = true
    description = "The configurations for the Emergency Invocation alarm. Threshold is the Number of Invocations, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm."
    validation {
        condition = (var.emergencyInvocations == null ? true : var.emergencyInvocations.evaluationPeriods > 0)
        error_message = "emergencyInvocations evaluationPeriods must be greater than 0"
    }
    validation {
        condition = (var.emergencyInvocations == null ? true : (var.emergencyInvocations.dataPointsToAlarm <= var.emergencyInvocations.evaluationPeriods && var.emergencyInvocations.dataPointsToAlarm > 0))
        error_message = "emergencyInvocations dataPointsToAlarm must be greater than 0 and less than or equal to emergencyInvocations evaluationPeriods"
    }
}
variable "emergencyCompute" {
    type = object({ threshold = number, period = number, statistic = string, evaluationPeriods = number, dataPointsToAlarm = number })
    default = null
    nullable = true
    description = "The configurations for the Emergency Compute alarm. Threshold is the Milliseconds of Compute, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm."
    validation {
        condition = (var.emergencyCompute == null ? true : contains(["Sum", "Average", "Maximum"], var.emergencyCompute.statistic))
        error_message = "Allowed values for emergencyCompute statistic are: \"Sum\", \"Average\", \"Maximum\"."
    }
    validation {
        condition = (var.emergencyCompute == null ? true : var.emergencyCompute.evaluationPeriods > 0)
        error_message = "emergencyCompute evaluationPeriods must be greater than 0"
    }
    validation {
        condition = (var.emergencyCompute == null ? true : (var.emergencyCompute.dataPointsToAlarm <= var.emergencyCompute.evaluationPeriods && var.emergencyCompute.dataPointsToAlarm > 0))
        error_message = "emergencyCompute dataPointsToAlarm must be greater than 0 and less than or equal to emergencyCompute evaluationPeriods"
    }
}
variable "panicInvocations" {
    type = object({ threshold = number, period = number, evaluationPeriods = number, dataPointsToAlarm = number })
    default = null
    nullable = true
    description = "The configurations for the Panic Invocations alarm. Threshold is the Number of Invocations, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm."
    validation {
        condition = (var.panicInvocations == null ? true : var.panicInvocations.evaluationPeriods > 0)
        error_message = "panicInvocations evaluationPeriods must be greater than 0"
    }
    validation {
        condition = (var.panicInvocations == null ? true : (var.panicInvocations.dataPointsToAlarm <= var.panicInvocations.evaluationPeriods && var.panicInvocations.dataPointsToAlarm > 0))
        error_message = "panicInvocations dataPointsToAlarm must be greater than 0 and less than or equal to panicInvocations evaluationPeriods"
    }
}
variable "panicCompute" {
    type = object({ threshold = number, period = number, statistic = string, evaluationPeriods = number, dataPointsToAlarm = number })
    default = null
    nullable = true
    description = "The configurations for the Panic Compute alarm. Threshold is the Milliseconds of Compute, Period is the timeframe in Minutes, and DataPointsToAlarm are how many periods need to breach in the number of EvaluationPeriods before an alarm is triggered. Assign null to not create this alarm."
    validation {
        condition = (var.panicCompute == null ? true : contains(["Sum", "Average", "Maximum"], var.panicCompute.statistic))
        error_message = "Allowed values for panicCompute statistic are: \"Sum\", \"Average\", \"Maximum\"."
    }
    validation {
        condition = (var.panicCompute == null ? true : var.panicCompute.evaluationPeriods > 0)
        error_message = "panicCompute evaluationPeriods must be greater than 0"
    }
    validation {
        condition = (var.panicCompute == null ? true : (var.panicCompute.dataPointsToAlarm <= var.panicCompute.evaluationPeriods && var.panicCompute.dataPointsToAlarm > 0))
        error_message = "panicCompute dataPointsToAlarm must be greater than 0 and less than or equal to panicCompute evaluationPeriods"
    }
}
variable "emergencyContact" {
    type = string
    nullable = true
    description = "The email address that will receive emails when alarms are triggered."
}

##########
# Outputs
##########


##########
# Resources
##########

locals {
  anyNotifications = (var.emergencyContact != null &&
  (var.emergencyInvocationsPerMinuteThreshold != null ||
  var.emergencyComputePerMinuteThreshold != null ||
  var.panicInvocationsPerMinuteThreshold != null ||
  var.panicComputePerMinuteThreshold != null ||
  var.emergencyInvocations != null ||
  var.emergencyCompute != null ||
  var.panicInvocations != null ||
  var.panicCompute != null))
}
resource "aws_sns_topic" "emergency" {
  count = local.anyNotifications ? 1 : 0
  name  = "demo-example_emergencies"
}
resource "aws_sns_topic_subscription" "emergency_primary" {
  count     = local.anyNotifications ? 1 : 0
  topic_arn = aws_sns_topic.emergency[0].arn
  protocol  = "email"
  endpoint  = var.emergencyContact
}
resource "aws_cloudwatch_metric_alarm" "emergency_minute_invocations" {
  count                     = local.anyNotifications && var.emergencyInvocationsPerMinuteThreshold != null ? 1 : 0
  alarm_name                = "demo-example_emergency_invocations"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Invocations"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = var.emergencyInvocationsPerMinuteThreshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}
resource "aws_cloudwatch_metric_alarm" "emergency_minute_compute" {
  count                     = local.anyNotifications && var.emergencyComputePerMinuteThreshold != null ? 1 : 0
  alarm_name                = "demo-example_emergency_compute"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Duration"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = var.emergencyComputePerMinuteThreshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}
resource "aws_cloudwatch_metric_alarm" "panic_minute_invocations" {
  count                     = local.anyNotifications && var.panicInvocationsPerMinuteThreshold != null ? 1 : 0
  alarm_name                = "demo-example_panic_invocations"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Invocations"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = var.panicInvocationsPerMinuteThreshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}
resource "aws_cloudwatch_metric_alarm" "panic_minute_compute" {
  count                     = local.anyNotifications && var.panicComputePerMinuteThreshold != null ? 1 : 0
  alarm_name                = "demo-example_panic_compute"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = "1"
  metric_name               = "Duration"
  namespace                 = "AWS/Lambda"
  period                    = "60"
  statistic                 = "Sum"
  threshold                 = var.panicComputePerMinuteThreshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}


resource "aws_cloudwatch_metric_alarm" "emergency_invocations" {
  count = (local.anyNotifications &&
  var.emergencyInvocations != null ?
    1 : 0)
  alarm_name                = "demo-example_emergency_invocations"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = var.emergencyInvocations.evaluationPeriods
  datapoints_to_alarm       = var.emergencyInvocations.dataPointsToAlarm
  metric_name               = "Invocations"
  namespace                 = "AWS/Lambda"
  period                    = var.emergencyInvocations.period * 60
  statistic                 = "Sum"
  threshold                 = var.emergencyInvocations.threshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}
resource "aws_cloudwatch_metric_alarm" "emergency_compute" {
  count = (local.anyNotifications &&
  var.emergencyCompute != null ?
    1 : 0)
  alarm_name                = "demo-example_emergency_compute"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = var.emergencyCompute.evaluationPeriods
  datapoints_to_alarm       = var.emergencyCompute.dataPointsToAlarm
  metric_name               = "Duration"
  namespace                 = "AWS/Lambda"
  period                    = var.emergencyCompute.period * 60
  statistic                 = var.emergencyCompute.statistic
  threshold                 = var.emergencyCompute.threshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}
resource "aws_cloudwatch_metric_alarm" "panic_invocations" {
  count = (local.anyNotifications &&
  var.panicInvocations != null ?
    1 : 0)
  alarm_name                = "demo-example_panic_invocations"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = var.panicInvocations.evaluationPeriods
  datapoints_to_alarm       = var.panicInvocations.dataPointsToAlarm
  metric_name               = "Invocations"
  namespace                 = "AWS/Lambda"
  period                    = var.panicInvocations.period * 60
  statistic                 = "Sum"
  threshold                 = var.panicInvocations.threshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}
resource "aws_cloudwatch_metric_alarm" "panic_compute" {
  count = (local.anyNotifications &&
  var.panicCompute != null ?
    1 : 0)
  alarm_name                = "demo-example_panic_compute"
  comparison_operator       = "GreaterThanOrEqualToThreshold"
  evaluation_periods        = var.panicCompute.evaluationPeriods
  datapoints_to_alarm       = var.panicCompute.dataPointsToAlarm
  metric_name               = "Duration"
  namespace                 = "AWS/Lambda"
  period                    = var.panicCompute.period * 60
  statistic                 = var.panicCompute.statistic
  threshold                 = var.panicCompute.threshold
  alarm_description         = ""
  insufficient_data_actions = []
  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
  alarm_actions = [aws_sns_topic.emergency[0].arn]
}


resource "aws_api_gateway_account" "main" {
  cloudwatch_role_arn = aws_iam_role.cloudwatch.arn
}

resource "aws_iam_role" "cloudwatch" {
  name = "demoexample"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "",
      "Effect": "Allow",
      "Principal": {
        "Service": ["apigateway.amazonaws.com", "lambda.amazonaws.com"]
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}
resource "aws_iam_role_policy" "cloudwatch" {
  name = "demoexample_policy"
  role = aws_iam_role.cloudwatch.id

  policy = <<EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:DescribeLogGroups",
                "logs:DescribeLogStreams",
                "logs:PutLogEvents",
                "logs:GetLogEvents",
                "logs:FilterLogEvents"
            ],
            "Resource": "*"
        }
    ]
}
EOF
}

