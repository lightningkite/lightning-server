package com.lightningkite.lightningserver.aws.terraform



internal fun awsEc2Handler(
    project: TerraformProjectInfo,
    otherSections: List<TerraformSection>,
) = TerraformSection(
    name = "ec2",
    providers = listOf(
        TerraformProvider(
            name = "tls",
            source = "hashicorp/tls",
            version = "~>4.0.6"
        ),
        TerraformProvider(
            name = "ssh",
            source = "loafoe/tls",
            version = "~>2.7.0"
        ),
    ),
    inputs = listOf(
        TerraformInput.string(
            "instance_ubuntu_version",
            "24.04",
            description = "The ubuntu LTS version to use"
        ),
        TerraformInput.string(
            "instance_size",
            "t3.micro",
            description = "The instance size to use; defaults to t2.micro"
        ),
        TerraformInput.string(
            "admin_ip",
            "0.0.0.0/32",
            description = "Permits SSH from this address"
        ),
        TerraformInput(
            "admins",
            "list(object({ username=string, name=string, site=string, phone1=string, phone2=string, email=string, keys=list(string) }))",
            description = "Keys for administrative access",
            default = null,
        )
    ),
    emit = {
        //language=HIL
        //
        appendLine("""
        
        resource "local_sensitive_file" "settings_raw" {
          content = jsonencode({
            ${
            otherSections.mapNotNull { it.toLightningServer }.flatMap { it.entries }.map { "${it.key} = ${it.value}" }
                .map { it.replace("\n", "\n            ") }.joinToString("\n            ")
        }})
          filename = "${'$'}{path.module}/build/raw-settings.json"
        }
        
        resource "aws_iam_role" "main_exec" {
          name = "${project.namePrefix}-main-exec"

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
        ${otherSections.flatMap { it.policies }.joinToString("\n") {
            """
        resource "aws_iam_role_policy_attachment" "$it" {
          role       = aws_iam_role.main_exec.id
          policy_arn = aws_iam_policy.$it.arn
        }
        """
        }}
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
            key_name = "${project.namePrefix}-terraform-deploy-key"
            public_key = tls_private_key.main.public_key_openssh
        }
        resource "aws_security_group" "main" {
          name        = "${project.namePrefix}-main"
          description = "The rules for the server"
          ${
            when {
                project.existingVpc -> "vpc_id      = data.aws_vpc.main.id"
                project.vpc -> "vpc_id      = aws_vpc.main.id"
                else -> ""
            }
        }
        
          tags = {
            Name = "${project.namePrefix}-main"
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
        
        locals {
          userCode = join("\n", [for x in var.admins : <<EOF
            sudo adduser ${'$'}{x.username} --gecos \"${'$'}{x.name},${'$'}{x.site},${'$'}{x.phone1},${'$'}{x.phone2},${'$'}{x.email}\"
            sudo mkdir /home/${'$'}{x.username}/.ssh
            sudo touch /home/${'$'}{x.username}/.ssh/authorized_keys
            echo "${'$'}{join("\n", x.keys)}\n" | sudo tee -a /home/${'$'}{x.username}/.ssh/authorized_keys
            sudo chmod 755 /home/${'$'}{x.username}/.ssh
            sudo chmod 664 /home/${'$'}{x.username}/.ssh/authorized_keys
          EOF
          ])
        }
        
        resource "aws_instance" "main" {
          ami = data.aws_ami.ubuntu.id
          instance_type = var.instance_size
          iam_instance_profile = aws_iam_instance_profile.main_exec.name
          key_name = aws_key_pair.main.key_name
          
          security_groups = [aws_security_group.main.id]
          subnet_id = ${project.privateSubnets}[0].id
          
          tags = {
            Name = "${project.projectName}"
          }
          user_data = <<EOF
              #!/bin/bash
              
              # Add some users
              ${'$'}{local.userCode}
          
              sudo apt update -y
              sudo apt upgrade -y
              sudo apt install -y build-essential vim git net-tools whois openjdk-17-jdk ca-certificates curl
              sudo curl -o /etc/apt/trusted.gpg.d/angie-signing.gpg https://angie.software/keys/angie-signing.gpg
              echo "deb https://download.angie.software/angie/${'$'}${'$'}(. /etc/os-release && echo "${'$'}${'$'}ID/${'$'}${'$'}VERSION_ID ${'$'}${'$'}VERSION_CODENAME") main" | sudo tee /etc/apt/sources.list.d/angie.list > /dev/null
              sudo apt upgrade -y
              sudo apt install -y angie
          EOF
        }
        resource "aws_eip" "main" {
          instance = aws_instance.main.id
          ${if(project.vpc) "domain   = \"vpc\"" else ""}
        }
        resource "aws_route53_record" "main" {
          zone_id = data.aws_route53_zone.main.zone_id
          name = var.domain_name
          type = "A"
          records = [aws_eip.main.public_ip]
          ttl = "300"
        }
    """.trimIndent(),
        )
    },
    outputs = listOf(
        TerraformOutput("private_key", "tls_private_key.main.private_key_pem", sensitive = true)
    )
)