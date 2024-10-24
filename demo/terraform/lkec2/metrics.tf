# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "metrics_tracked" {
    type = list(string)
    default = ["Execution Time", "Health Checks Run"]
    nullable = false
}
variable "metrics_namespace" {
    type = string
    default = "demo"
    nullable = false
}

##########
# Outputs
##########


##########
# Resources
##########

resource "aws_iam_policy" "metrics" {
  name        = "demo-metrics"
  path = "/demo/metrics/"
  description = "Access to publish metrics"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "cloudwatch:PutMetricData",
        ]
        Effect   = "Allow"
        Condition = {
            StringEquals = {
                "cloudwatch:namespace": var.metrics_namespace
            }
        }
        Resource = ["*"]
      },
    ]
  })
}
