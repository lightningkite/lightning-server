$env:AWS_PROFILE = "default"
. ~/.mongo/profiles/default.ps1
terraform $args
