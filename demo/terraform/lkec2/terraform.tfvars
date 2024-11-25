cors = null
display_name = "demo-example-single-ec2"
database_org_id     = "6323a65c43d66b56a2ea5aea"
database_continuous_backup = false
jwt_expiration = "PT8760H"
jwt_emailExpiration = "PT1H"
sms = {"url":"console","from":null}
logging = {"default":{"filePattern":null,"toConsole":true,"level":"INFO","additive":false},"logger":null}
files_expiry = "P1D"
metrics_tracked = ["Execution Time", "Health Checks Run"]
metrics_namespace = "demo-example-single-ec2"
exceptions = {"url":"none","sentryDsn":null}
reporting_email = "joseph@lightningkite.com"
deployment_location = "us-west-2"
debug = true
ip_prefix = "10.0"
domain_name_zone = "cs.lightningkite.com"
domain_name = "lsdemo.cs.lightningkite.com"
admins = [
  {
    username = "jivie"
    email = "joseph@lightningkite.com"
    name = "Joseph Ivie"
    site = "lightningkite.com"
    phone1 = "8013693729"
    phone2 = ""
    keys = [
      "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDpQPSzK//RcMqYjkSdvjByZCjMNHR4A5LNFhZ9K1AblyX7TH2XEYVVvGgDzZ49+aal7UdugOgnIBWfXZZ4dlUAmfnePJfHqZ5Sapj13YrfGWLkXuie6e8hkj3V5FY5TVHIGxq0qe/rA20Ur8yRAwJSMnDOun/gq4+GjVQrtKRx0zAzDNH3QvJS79bcBYSKf7BodAXcUE2JY0JipBDlm321dJBWoFMEI1rOYJElF2fKMpZ0Y7eAxoL2MisI1addD3Vo8J+RGqrA2zJbuccDXZt+5R5ej8AOPYShhuFVfQRTG0N8AxSn3WtNg7Xj8wRvtMgPRtTAplNnoU2nUl0p3jjgGQU5/NawQkUR3B+gkv3FBZSxHJ1AnJzqOizBmfIHwm2dMDXeASUri9JJKzPvn9o1QzeM22UkBt7Oo81Ie4c4mwgTaieEo7oxHLQeyl01gNkGBADg35RgzdBqBvQzJDXEkIFhD6AD/tbpCns8iP63J0jRGQ4xxAzRi1BCnOhphnc= joseph@joseph-ThinkPad-E15"
    ]
  }
]
vpc_id = "vpc-96e035f1"
vpc_private_subnets = ["subnet-e2838d94"]
vpc_nat_gateways = ["nat-0eb91da9459f05922"]
admin_ip = "75.148.99.49/32"