$env:AWS_PROFILE = "lk"
. ~/.mongo/profiles/lk.ps1
terraform $args
