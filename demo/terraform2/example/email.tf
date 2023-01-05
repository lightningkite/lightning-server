##########
# Inputs
##########

variable "reporting_email" {
    type = string
}

##########
# Outputs
##########


##########
# Resources
##########

resource "aws_iam_user" "email" {
  name = "demo-example-email-user"
}

resource "aws_iam_access_key" "email" {
  user = aws_iam_user.email.name
}

data "aws_iam_policy_document" "email" {
  statement {
    actions   = ["ses:SendRawEmail"]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "email" {
  name = "demo-example-email-policy"
  description = "Allows sending of e-mails via Simple Email Service"
  policy      = data.aws_iam_policy_document.email.json
}

resource "aws_iam_user_policy_attachment" "email" {
  user       = aws_iam_user.email.name
  policy_arn = aws_iam_policy.email.arn
}

resource "aws_ses_domain_identity" "email" {
  domain = var.domain_name
}
resource "aws_ses_domain_mail_from" "email" {
  domain           = aws_ses_domain_identity.email.domain
  mail_from_domain = "mail.${var.domain_name}"
}
resource "aws_route53_record" "email_mx" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_ses_domain_mail_from.email.mail_from_domain
  type    = "MX"
  ttl     = "600"
  records = ["10 feedback-smtp.${var.deployment_location}.amazonses.com"] # Change to the region in which `aws_ses_domain_identity.example` is created
}
resource "aws_route53_record" "email" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "_amazonses.${var.domain_name}"
  type    = "TXT"
  ttl     = "600"
  records = [aws_ses_domain_identity.email.verification_token]
}
resource "aws_ses_domain_dkim" "email_dkim" {
  domain = aws_ses_domain_identity.email.domain
}
resource "aws_route53_record" "email_spf_mail_from" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_ses_domain_mail_from.email.mail_from_domain
  type    = "TXT"
  ttl     = "300"
  records = [
    "v=spf1 include:amazonses.com -all"
  ]
}
resource "aws_route53_record" "email_spf_domain" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_ses_domain_identity.email.domain
  type    = "TXT"
  ttl     = "300"
  records = [
    "v=spf1 include:amazonses.com -all"
  ]
}
resource "aws_route53_record" "email_dkim_records" {
  count   = 3
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${element(aws_ses_domain_dkim.email_dkim.dkim_tokens, count.index)}._domainkey.${var.domain_name}"
  type    = "CNAME"
  ttl     = "300"
  records = [
    "${element(aws_ses_domain_dkim.email_dkim.dkim_tokens, count.index)}.dkim.amazonses.com",
  ]
}
resource "aws_route53_record" "email_route_53_dmarc_txt" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "_dmarc.${var.domain_name}"
  type    = "TXT"
  ttl     = "300"
  records = [
    "v=DMARC1;p=quarantine;pct=75;rua=mailto:${var.reporting_email}"
  ]
}

