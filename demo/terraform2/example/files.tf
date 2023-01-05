##########
# Inputs
##########

variable "files_expiry" {
    type = string
    default = "P1D"
}

##########
# Outputs
##########


##########
# Resources
##########

resource "aws_s3_bucket" "files" {
  bucket_prefix = "demo-example-files"
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
resource "aws_s3_bucket_policy" "files" {  
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
resource "aws_s3_bucket_acl" "files" {
  bucket = aws_s3_bucket.files.id
  acl    = var.files_expiry == null ? "public-read" : "private" 
}
resource "aws_iam_policy" "files" {
  name        = "demo-example-files"
  path = "/demo/example/files/"
  description = "Access to the demo-example_files bucket"
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
resource "aws_iam_role_policy_attachment" "files" {
  role       = aws_iam_role.main_exec.name
  policy_arn = aws_iam_policy.files.arn
}

