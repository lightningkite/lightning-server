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
        wsUrl = "wss://ws.${var.domain_name}"
        debug = var.debug
        cors = var.cors
        host = "127.0.0.1"
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
}

resource "ssh_resource" "main_install_resources" {
  depends_on = [aws_instance.main]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
    admins = jsonencode(var.admins)
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  commands = [
    "sudo apt update -y",
    "sudo apt upgrade -y",
    "sudo apt install -y build-essential vim git net-tools whois openjdk-17-jdk ca-certificates curl supervisor libssl-dev pkg-config unzip nginx certbot python3-certbot-nginx rustup",
  ]
  timeout = "5m"
}
resource "ssh_resource" "main_install_efs" {
  depends_on = [aws_instance.main, ssh_resource.main_install_resources]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
    admins = jsonencode(var.admins)
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  file {
    destination = "install-efs.sh"
    content = <<EOF
      #!/bin/bash
      # EFS utils install
      if ! dpkg -s amazon-efs-utils &>/dev/null; then
        if [ ! -d "~/efs-utils/" ]; then
                mkdir ~/efs-utils
        fi

        git clone https://github.com/aws/efs-utils ~/efs-utils

        cd ~/efs-utils && ./build-deb.sh

        sudo apt -y install ~/efs-utils/build/amazon-efs-utils*deb

        sudo rm -rf ~/efs-utils
      fi
    EOF
    permissions = "0700"
  }
  commands = [
    "rustup default stable",
    "/bin/bash install-efs.sh",
  ]
  timeout = "10m"
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
resource "aws_route53_record" "ws" {
  zone_id = data.aws_route53_zone.main.zone_id
  name = "ws.${var.domain_name}"
  type = "A"
  records = [aws_eip.main.public_ip]
  ttl = "300"
}

resource "ssh_resource" "main_users" {
  depends_on = [aws_instance.main]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
    admins = jsonencode(var.admins)
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  commands = flatten([for x in var.admins : [
    "sudo adduser ${x.username} --gecos \"${x.name},${x.site},${x.phone1},${x.phone2},${x.email}\" || true",
    "echo \"${x.username}:changeme\" | sudo chpasswd ${x.username}",
    "sudo mkdir -p /home/${x.username}/.ssh",
    "printf \"${join("\n", x.keys)}\n\" | sudo tee /home/${x.username}/.ssh/authorized_keys",
    "sudo chmod 755 /home/${x.username}/.ssh",
    "sudo chmod 664 /home/${x.username}/.ssh/authorized_keys",
    "sudo chown ${x.username}:${x.username} /home/${x.username}/.ssh -R",
    "sudo adduser ${x.username} sudo",
  ]])
  timeout = "20s"
}

resource "ssh_resource" "main_mount_efs" {
  depends_on = [ssh_resource.main_install_efs]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
    systemid = aws_efs_file_system.main.id
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  commands = [
    "sudo [ -d /mnt/efs ] || sudo mkdir /mnt/efs",
    "sudo mount -t efs ${aws_efs_file_system.main.id} /mnt/efs/"
  ]
  timeout = "20s"
}

resource "aws_efs_mount_target" "main" {
  for_each = data.aws_subnet.private
  file_system_id = aws_efs_file_system.main.id
  subnet_id      = data.aws_subnet.private[each.key].id
  security_groups = [aws_security_group.main_efs.id]
  # TODO: Add security group with port 2049, internal only
}

resource "aws_security_group" "main_efs" {
  name   = "demo-example-single-ec2-efs"
  vpc_id = data.aws_vpc.main.id

  ingress {
    description     = "EFS"
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    cidr_blocks = [data.aws_vpc.main.cidr_block]
  }
  egress {
    from_port       = 0
    to_port         = 0
    protocol        = "-1"
    cidr_blocks = [data.aws_vpc.main.cidr_block]
  }
}

resource "aws_efs_file_system" "main" {
  tags = {
    Name = "demo-example-single-ec2"
  }
}

resource "ssh_resource" "upload_executable" {
  depends_on = [ssh_resource.main_install_resources, ssh_resource.main_install_efs, ssh_resource.main_mount_efs]
  host = aws_eip.main.public_ip
  triggers = {
    systemid = aws_efs_file_system.main.id
    index = filesha512("../../build/distributions/server.zip")
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  file {
    source = "../../build/distributions/server.zip"
    destination = "${var.domain_name}.zip"
  }
  commands = [
    "sudo groupadd -g 4200 server_runner || true",
    "sudo useradd -r -u 400 -g server_runner server_runner || true",
    "sudo unzip -o ${var.domain_name}.zip -d /mnt/efs/${var.domain_name}",
    "sudo chown -R server_runner:server_runner /mnt/efs/${var.domain_name}",
    "sudo chmod -R 500 /mnt/efs/${var.domain_name}",
  ]
  timeout = "30s"
}

resource "ssh_resource" "upload_settings" {
  depends_on = [ssh_resource.main_install_resources, ssh_resource.main_mount_efs, ssh_resource.upload_executable]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
    systemid = aws_efs_file_system.main.id
    settingshash = local_sensitive_file.settings_raw.content_base64sha512
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  file {
    source = local_sensitive_file.settings_raw.filename
    destination = "${var.domain_name}.settings.json"
  }
  commands = [
    "sudo groupadd -g 4200 server_runner || true",
    "sudo useradd -r -u 400 -g server_runner server_runner || true",
    "sudo mv ${var.domain_name}.settings.json /mnt/efs/${var.domain_name}/server/bin/settings.json",
    "sudo chown -R server_runner:server_runner /mnt/efs/${var.domain_name}",
    "sudo chmod -R 500 /mnt/efs/${var.domain_name}",
  ]
  timeout = "30s"
}

resource "ssh_resource" "setup_nginx" {
  depends_on = [ssh_resource.main_install_resources, ssh_resource.main_mount_efs, ssh_resource.upload_executable, aws_route53_record.main]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  file {
    content = <<EOF
    user www-data;
    worker_processes auto;
    pid /run/nginx.pid;
    error_log /var/log/nginx/error.log;
    include /etc/nginx/modules-enabled/*.conf;

    events {
            worker_connections 768;
            # multi_accept on;
    }

    http {

            ##
            # Basic Settings
            ##

            sendfile on;
            tcp_nopush on;
            types_hash_max_size 2048;
            # server_tokens off;

            # server_names_hash_bucket_size 64;
            # server_name_in_redirect off;

            include /etc/nginx/mime.types;
            default_type application/octet-stream;

            ##
            # SSL Settings
            ##

            ssl_protocols TLSv1 TLSv1.1 TLSv1.2 TLSv1.3; # Dropping SSLv3, ref: POODLE
            ssl_prefer_server_ciphers on;

            ##
            # Logging Settings
            ##

            access_log /var/log/nginx/access.log;

            ##
            # Gzip Settings
            ##

            gzip on;

            # gzip_vary on;
            # gzip_proxied any;
            # gzip_comp_level 6;
            # gzip_buffers 16 8k;
            # gzip_http_version 1.1;
            # gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

            ##
            # Virtual Host Configs
            ##

            log_format upstreamlog '[$time_local] $remote_addr - $remote_user - $server_name $host to: $upstream_addr: $request $status upstream_response_time $upstream_response_time msec $msec request_time $request_time';
            include /etc/nginx/conf.d/*.conf;
            include /etc/nginx/sites-enabled/*;
    }
    EOF
    destination = "top-nginx.conf"
  }
  file {
    content = <<EOF
    server {
      listen 80;
      server_name ${var.domain_name};

      location / {
        access_log /var/log/nginx/${var.domain_name}-upstream.log upstreamlog;

        proxy_next_upstream error timeout;

        proxy_pass http://127.0.0.1:8080;
        proxy_pass_header Server;
        proxy_redirect off;
        proxy_set_header Host $http_host;
        proxy_set_header X-Scheme $scheme;
        proxy_set_header REMOTE_ADDR $remote_addr;
        proxy_set_header X-Forwarded-Host $server_name;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        add_header P3P 'CP="ALL DSP COR PSAa PSDa OUR NOR ONL UNI COM NAV"';
        add_header Host $http_host;
        proxy_connect_timeout 130s;
        proxy_read_timeout 130s;
      }
    }
    EOF
    destination = "http.conf"
  }
  file {
    content = <<EOF
    server {
      listen 80;
      server_name ws.${var.domain_name};

      location / {
        access_log /var/log/nginx/ws.${var.domain_name}-upstream.log upstreamlog;

        proxy_next_upstream error timeout;

        proxy_pass http://127.0.0.1:8080;
        proxy_pass_header Server;
        proxy_http_version 1.1;
        proxy_redirect off;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Scheme $scheme;
        proxy_set_header REMOTE_ADDR $remote_addr;
        proxy_set_header X-Forwarded-Host $server_name;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        add_header P3P 'CP="ALL DSP COR PSAa PSDa OUR NOR ONL UNI COM NAV"';
        add_header Host $http_host;
      }
    }
    EOF
    destination = "ws.conf"
  }
  commands = [
    "sudo mv -f top-nginx.conf /etc/nginx/nginx.conf",
    "sudo mv -f http.conf /etc/nginx/sites-available/${var.domain_name}.conf",
    "sudo mv -f ws.conf /etc/nginx/sites-available/ws.${var.domain_name}.conf",
    "sudo ln -sf /etc/nginx/sites-available/${var.domain_name}.conf /etc/nginx/sites-enabled/${var.domain_name}.conf",
    "sudo ln -sf /etc/nginx/sites-available/ws.${var.domain_name}.conf /etc/nginx/sites-enabled/ws.${var.domain_name}.conf",
    "sudo certbot --nginx -d ${var.domain_name} -d ws.${var.domain_name} --non-interactive --agree-tos -m ${var.reporting_email}"
  ]
  timeout = "60s"
}

resource "ssh_resource" "setup_supervisor" {
  depends_on = [ssh_resource.main_mount_efs]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  file {
    content = <<EOF
      [program:${var.domain_name}]
      directory=/mnt/efs/${var.domain_name}/server/bin
      user=server_runner
      command=/bin/sh server serve
      autostart=true
      autorestart=true
      stderr_logfile=/var/log/${var.domain_name}.err.log
      stdout_logfile=/var/log/${var.domain_name}.out.log
    EOF
    destination = "${var.domain_name}-supervisor.conf"
  }
  commands = [
    "sudo mv ${var.domain_name}-supervisor.conf /etc/supervisor/conf.d/${var.domain_name}.conf",
    "sudo supervisorctl reload",
  ]
  timeout = "30s"
}

resource "ssh_resource" "restart_server" {
  depends_on = [ssh_resource.setup_supervisor, ssh_resource.upload_executable, ssh_resource.upload_settings]
  host = aws_eip.main.public_ip
  triggers = {
    instanceid = aws_instance.main.id
    systemid = aws_efs_file_system.main.id
    index = filesha512("../../build/distributions/server.zip")
    settings = local_sensitive_file.settings_raw.content_base64sha512
  }
  user = "ubuntu"
  password = ""
  private_key = tls_private_key.main.private_key_openssh
  commands = [
    "sudo supervisorctl restart ${var.domain_name}",
  ]
  timeout = "30s"
}

