# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "files_expiry" {
    type = string
    default = "P1D"
    nullable = true
}

##########
# Outputs
##########


##########
# Resources
##########

resource "aws_s3_bucket" "files" {
  bucket_prefix = "demo-example-single-ec2-files"
  force_destroy = var.debug
}
resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.files.bucket

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT", "POST"]
    allowed_origins = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["*"]
  }
}
resource "aws_s3_bucket_public_access_block" "files" {
  count = var.files_expiry == null ? 1 : 0
  bucket = aws_s3_bucket.files.id

  block_public_acls   = false
  block_public_policy = false
  ignore_public_acls = false
  restrict_public_buckets = false
}
resource "aws_s3_bucket_policy" "files" {  
  depends_on = [aws_s3_bucket_public_access_block.files]
  count = var.files_expiry == null ? 1 : 0
  bucket = aws_s3_bucket.files.id   
  policy = <<POLICY
{    
    "Version": "2012-10-17",    
    "Statement": [        
      {            
          "Sid": "PublicReadGetObject",            
          "Effect": "Allow",            
          "Principal": "*",            
          "Action": [                
             "s3:GetObject"            
          ],            
          "Resource": [
             "arn:aws:s3:::${aws_s3_bucket.files.id}/*"            
          ]        
      }    
    ]
}
POLICY
}
# resource "aws_s3_bucket_acl" "files" {
#   bucket = aws_s3_bucket.files.id
#   acl    = var.files_expiry == null ? "public-read" : "private" 
# }
resource "aws_iam_policy" "files" {
  name        = "demo-example-single-ec2-files"
  path = "/demo/example/single/ec2/files/"
  description = "Access to the demo-example-single-ec2_files bucket"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "s3:*",
        ]
        Effect   = "Allow"
        Resource = [
            "${aws_s3_bucket.files.arn}",
            "${aws_s3_bucket.files.arn}/*",
        ]
      },
    ]
  })
}

