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