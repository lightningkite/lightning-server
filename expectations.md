# Expectations for Engines

- X-Content-Type-Options: nosniff
- (if public URL has https) Strict-Transport-Security: max-age=3600
- HEAD - if undefined, perform get and ignore body
- Range - respected, ideally abuses extra options to do proper file partial fetch
- OPTIONS
  - Allow: OPTIONS, GET, HEAD, POST
  - Accept-Post: (insert supported media types)
  - Accept-Patch: (insert supported media types)
  - Accept-Ranges: bytes
  - Accept-Encoding: br, gzip, deflate
  - 
- CORS (for both OPTIONS and other requests)


Accept-Language support?

# Expectations for Deployment

- AWS Lambda
  - In code
    - Define which services to use
    - Define other constants
  - On Running
    - Generate Terraform
    - Prompt for secret insertion if secrets aren't present
    - Execute Terraform init with retrieved secrets
    - Execute Terraform apply with retrieved secrets
- SSH to Existing Machine
  - In code
    - Define serialized settings
  - On Running
    - SCP's the executable to the machine
    - (if needed) Get needed JDK
    - (if needed) Establish Supervisor config
    - (if needed) Establish NGINX/Angie config
    - (if needed) Establish settings file path
    - Supervisor restart