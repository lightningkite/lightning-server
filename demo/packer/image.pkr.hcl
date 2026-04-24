# Copyright (c) HashiCorp, Inc.
# SPDX-License-Identifier: MPL-2.0

packer {
  required_plugins {
    amazon = {
      version = "~> 1"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "region" {
  type    = string
  default = "us-west-2"
}

locals { timestamp = regex_replace(timestamp(), "[- TZ:]", "") }


# source blocks are generated from your builders; a source can be referenced in
# build blocks. A build block runs provisioners and post-processors on a
# source.
source "amazon-ebs" "main" {
  ami_name      = "ls-demo-${local.timestamp}"
  instance_type = "t2.micro"
  region        = var.region
  source_ami_filter {
    filters = {
      name                = "ubuntu/images/*ubuntu-noble-24.04-amd64-server-*"
      root-device-type    = "ebs"
      virtualization-type = "hvm"
    }
    most_recent = true
    owners = ["099720109477"]
  }
  ssh_username = "ubuntu"
  vpc_id       = "vpc-96e035f1"
  subnet_id    = "subnet-e2838d94"
}

# a build block invokes sources and runs provisioning steps on them.
build {
  sources = ["source.amazon-ebs.main"]

  provisioner "file" {
    source      = "../build/distributions/server.zip"
    destination = "/opt/my-server.zip"
  }
  provisioner "shell" {
    inline = [
      "apt install openjdk-17-jdk",
      "groupadd -g 4200 server_runner || true",
      "useradd -r -u 400 -g server_runner server_runner || true",
      "unzip -o /opt/my-server.zip -d /opt/my-server",
      "chown -R server_runner:server_runner /opt/my-server",
      "chmod -R 500 /opt/my-server",
    ]
  }
  provisioner "file" {
    content     = <<EOF
      [Unit]
      Description=MyServer

      [Service]
      ExecStart=/etc/my-server/bin/server --settings ~/settings.json serve
      Restart=always
      User=server_runner

      [Install]
      WantedBy=multi-user.target
    EOF
    destination = "/etc/systemd/system/my-server.service"
  }
  provisioner "shell" {
    inline = [
      "systemctl --user daemon-reload",
      # "systemctl --user enable my-server",
      # "systemctl --user start my-server",
    ]
  }
}