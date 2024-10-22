# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating.
##########
# Inputs
##########

variable "instance_ubuntu_version" {
    type = string
    default = "24.04"
    nullable = false
    description = "The ubuntu LTS version to use"
}
variable "instance_size" {
    type = string
    default = "t3.micro"
    nullable = false
    description = "The instance size to use; defaults to t2.micro"
}
variable "admin_ip" {
    type = string
    default = "0.0.0.0/32"
    nullable = false
    description = "Permits SSH from this address"
}
variable "admins" {
    type = list(object({ username=string, name=string, site=string, phone1=string, phone2=string, email=string, keys=list(string) }))
    nullable = false
    description = "Keys for administrative access"
}

##########
# Outputs
##########

output "private_key" {
    value = tls_private_key.main.private_key_pem
    sensitive = true
}

##########
# Resources
##########


resource "local_sensitive_file" "settings_raw" {
  content = jsonencode({
    general = {
        projectName = var.display_name
        publicUrl = "https://${var.domain_name}"
        wsUrl = "wss://ws.${var.domain_name}?path="
        debug = var.debug
        cors = var.cors
    }
    database = {
      url = "mongodb+srv://demoexamplesingleec2database-main:${random_password.database.result}@${replace(mongodbatlas_serverless_instance.database.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority"
    }
    cache = {
        url = "dynamodb://${var.deployment_location}/demo_example_single_ec2"
    }
    secretBasis = random_password.secretBasis.result
    jwt = {
        expiration = var.jwt_expiration 
        emailExpiration = var.jwt_emailExpiration 
        secret = random_password.jwt.result
    }
    sms = var.sms
    logging = var.logging
    files = {
        storageUrl = "s3://${aws_s3_bucket.files.id}.s3-${aws_s3_bucket.files.region}.amazonaws.com"
        signedUrlExpiration = var.files_expiry
    }
    metrics = {
        url = "cloudwatch://${var.deployment_location}/${var.metrics_namespace}"
        trackingByEntryPoint = var.metrics_tracked
    }
    exceptions = var.exceptions
    email = {
        url = "smtp://${aws_iam_access_key.email.id}:${aws_iam_access_key.email.ses_smtp_password_v4}@email-smtp.${var.deployment_location}.amazonaws.com:587" 
        fromEmail = "noreply@${var.domain_name}"
    }})
  filename = "${path.module}/build/raw-settings.json"
}

resource "aws_iam_role" "main_exec" {
  name = "demo-example-single-ec2-main-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
      Effect = "Allow"
      Sid = ""
    }]
  })
}

resource "aws_iam_role_policy_attachment" "files" {
  role       = aws_iam_role.main_exec.id
  policy_arn = aws_iam_policy.files.arn
}


resource "aws_iam_role_policy_attachment" "metrics" {
  role       = aws_iam_role.main_exec.id
  policy_arn = aws_iam_policy.metrics.arn
}

resource "aws_iam_instance_profile" "main_exec" {
  name = "main_exec"
  role = aws_iam_role.main_exec.name
}
data "aws_ami" "ubuntu" {
  most_recent = true

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/*24.04*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "description"
    values = ["*LTS*"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  owners = ["099720109477"] # Canonical
}
resource "tls_private_key" "main" {
  algorithm = "RSA"
  rsa_bits  = 4096
}
resource "aws_key_pair" "main" {
    key_name = "demo-example-single-ec2-terraform-deploy-key"
    public_key = tls_private_key.main.public_key_openssh
}
resource "aws_security_group" "main" {
  name        = "demo-example-single-ec2-main"
  description = "The rules for the server"
  vpc_id      = data.aws_vpc.main.id

  tags = {
    Name = "demo-example-single-ec2-main"
  }
}
resource "aws_vpc_security_group_ingress_rule" "main_allow_http" {
  security_group_id = aws_security_group.main.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  ip_protocol       = "tcp"
  to_port           = 80
}
resource "aws_vpc_security_group_ingress_rule" "main_allow_https" {
  security_group_id = aws_security_group.main.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  ip_protocol       = "tcp"
  to_port           = 443
}
resource "aws_vpc_security_group_ingress_rule" "allow_tls_ipv4" {
  security_group_id = aws_security_group.main.id
  cidr_ipv4         = var.admin_ip
  from_port         = 22
  ip_protocol       = "tcp"
  to_port           = 22
}
resource "aws_vpc_security_group_egress_rule" "allow_tls_ipv4" {
  security_group_id = aws_security_group.main.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol = "-1"
}

resource "aws_instance" "main" {
  ami = data.aws_ami.ubuntu.id
  instance_type = var.instance_size
  iam_instance_profile = aws_iam_instance_profile.main_exec.name
  key_name = aws_key_pair.main.key_name

  # vpc = data.aws_vpc.main.arn
  vpc_security_group_ids = [aws_security_group.main.id]
  subnet_id     = data.aws_subnet.private["subnet-e2838d94"].id

  tags = {
    Name = "demo-example-single-ec2"
  }
  user_data = <<EOF
      #!/bin/bash
      sudo apt update -y
      sudo apt upgrade -y
      sudo apt install -y build-essential vim git net-tools whois openjdk-17-jdk ca-certificates curl supervisor
      sudo curl -o /etc/apt/trusted.gpg.d/angie-signing.gpg https://angie.software/keys/angie-signing.gpg
      echo "deb https://download.angie.software/angie/$(. /etc/os-release && echo "$ID/$VERSION_ID $VERSION_CODENAME") main" | sudo tee /etc/apt/sources.list.d/angie.list
      sudo apt upgrade -y
      sudo apt install -y angie
  EOF
}
resource "aws_eip" "main" {
  instance = aws_instance.main.id
}
resource "aws_route53_record" "main" {
  zone_id = data.aws_route53_zone.main.zone_id
  name = var.domain_name
  type = "A"
  records = [aws_eip.main.public_ip]
  ttl = "300"
}

resource "ssh_resource" "main_users" {
  depends_on = [aws_instance.main]
  host = aws_eip.main.public_ip
  triggers = {
    admins = jsonencode(var.admins)
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  commands = flatten([for x in var.admins : [
    "sudo adduser ${x.username} --disabled-password --gecos \"${x.name},${x.site},${x.phone1},${x.phone2},${x.email}\" sudo",
    "sudo mkdir -p /home/${x.username}/.ssh",
    "printf \"${join("\n", x.keys)}\n\" | sudo tee /home/${x.username}/.ssh/authorized_keys",
    "sudo chmod 755 /home/${x.username}/.ssh",
    "sudo chmod 664 /home/${x.username}/.ssh/authorized_keys",
    "sudo chown ${x.username}:${x.username} /home/${x.username}/.ssh -R",
  ]])
  timeout = "20s"
}

resource "ssh_resource" "main_mount_efs" {
  depends_on = [aws_instance.main]
  host = aws_eip.main.public_ip
  triggers = {
    admins = jsonencode(var.admins)
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  commands = [
    "sudo mount -t efs ${aws_efs_file_system.main.id} efs/"
  ]
  timeout = "20s"
}

resource "aws_efs_file_system" "main" {
  tags = {
    Name = "demo-example-single-ec2"
  }
}

# resource "ssh_resource" "main_efs" {
#   depends_on = [aws_instance.main]
#   host = aws_eip.main.public_ip
#   triggers = [var.admins]
#   user = "ubuntu"
#   private_key = tls_private_key.main.private_key_openssh
#   commands = []
# }

# resource "ssh_resource" "main_dist" {
#   depends_on = [aws_instance.main]
#   host = aws_eip.main.public_ip
#   triggers = [var.admins]
#   user = "ubuntu"
#   private_key = tls_private_key.main.private_key_openssh
#   file {
#     source = "${path.module}../../build/distributions/demo.zip"
#     destination = ""
#   }
#   commands = flatten([for x in var.admins : [
#     "sudo adduser ${x.username} --gecos \"${x.name},${x.site},${x.phone1},${x.phone2},${x.email}\" sudo",
#     "sudo mkdir /home/${x.username}/.ssh",
#     "sudo touch /home/${x.username}/.ssh/authorized_keys",
#     "printf \"${join("\n", x.keys)}\n\" | sudo tee -a /home/${x.username}/.ssh/authorized_keys",
#     "sudo chmod 755 /home/${x.username}/.ssh",
#     "sudo chmod 664 /home/${x.username}/.ssh/authorized_keys",
#   ]])
# }


