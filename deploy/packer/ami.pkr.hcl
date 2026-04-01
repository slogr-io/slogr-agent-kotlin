packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = ">= 1.3.0"
    }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "version" {
  type    = string
  default = "1.0.0"
}

variable "base_ami_id" {
  type        = string
  default     = ""
  description = "Base AMI ID (Amazon Linux 2023). Leave empty to auto-select latest."
}

locals {
  timestamp = formatdate("YYYYMMDD-HHmmss", timestamp())
  ami_name  = "slogr-agent-${var.version}-${local.timestamp}"
}

data "amazon-ami" "al2023" {
  count   = var.base_ami_id == "" ? 1 : 0
  owners  = ["amazon"]
  filters = {
    name                = "al2023-ami-2023*-kernel-6*-x86_64"
    root-device-type    = "ebs"
    virtualization-type = "hvm"
  }
  most_recent = true
  region      = var.region
}

source "amazon-ebs" "slogr_agent" {
  region        = var.region
  ami_name      = local.ami_name
  instance_type = "t3.micro"
  source_ami    = var.base_ami_id != "" ? var.base_ami_id : data.amazon-ami.al2023[0].id
  ssh_username  = "ec2-user"

  launch_block_device_mappings {
    device_name           = "/dev/xvda"
    volume_size           = 8
    volume_type           = "gp3"
    delete_on_termination = true
  }

  tags = {
    Name          = local.ami_name
    Version       = var.version
    ManagedBy     = "packer"
    "slogr:agent" = var.version
  }
}

build {
  sources = ["source.amazon-ebs.slogr_agent"]

  # Install Amazon Corretto 21
  provisioner "shell" {
    inline = [
      "sudo yum update -y",
      "sudo yum install -y java-21-amazon-corretto-headless",
      "sudo mkdir -p /opt/slogr/{lib,data,logs} /etc/slogr",
      "sudo useradd -r -s /sbin/nologin -d /opt/slogr slogr || true",
      "sudo chown -R slogr:slogr /opt/slogr",
      "sudo touch /etc/slogr/env && sudo chmod 600 /etc/slogr/env",
    ]
  }

  # Upload artifacts
  provisioner "file" {
    source      = "../../app/build/libs/slogr-agent-all.jar"
    destination = "/tmp/slogr-agent-all.jar"
  }

  provisioner "file" {
    source      = "../../native/build/libs/libslogr-native-linux-amd64.so"
    destination = "/tmp/libslogr-native.so"
  }

  provisioner "file" {
    source      = "../wrapper.sh"
    destination = "/tmp/slogr-agent"
  }

  provisioner "file" {
    source      = "../slogr-agent.service"
    destination = "/tmp/slogr-agent.service"
  }

  # Install artifacts
  provisioner "shell" {
    inline = [
      "sudo mv /tmp/slogr-agent-all.jar /opt/slogr/",
      "sudo mv /tmp/libslogr-native.so /opt/slogr/lib/",
      "sudo chown slogr:slogr /opt/slogr/slogr-agent-all.jar /opt/slogr/lib/libslogr-native.so",
      "sudo cp /tmp/slogr-agent /usr/bin/slogr-agent",
      "sudo chmod 755 /usr/bin/slogr-agent",
      "sudo cp /tmp/slogr-agent.service /etc/systemd/system/slogr-agent.service",
      "sudo systemctl daemon-reload",
      "sudo systemctl enable slogr-agent",
    ]
  }

  # Emit a manifest.json with AMI ID — CI reads it to update the SSM parameter (ADR-005)
  post-processor "manifest" {
    output     = "manifest.json"
    strip_path = true
  }
}
