# Lightning Server TODO List

**Generated:** 2025-11-10
**Source:** SECURITY_AND_QUALITY_ISSUES.md + API suggestions from codebase

---

## 🟠 MEDIUM PRIORITY Parsing Issues (Priority 7-9)

- [ ] Fix HttpHeaderValue parsing for quoted values with semicolons: `filename="file; with; semicolons.txt"` (
  core/.../http/HttpHeaderValue.kt)
- [ ] Fix HttpHeaderValue cookie parsing for values without `=`
- [ ] Fix ServerSettings properties parsing for values containing `=` (splits incorrectly) (
  core/.../settings/ServerSettings.ext.kt)
- [ ] Fix ServerSettings properties parsing for values containing `#` (comment handling too aggressive)

## 🟢 THREAD SAFETY (Priority 10-12)

- [ ] (Claude) Make ServerSetting cached implementations thread-safe using lazy(LazyThreadSafetyMode.SYNCHRONIZED) (
  core/.../definition/ServerSetting.kt)
- [ ] (Claude) Make SecretBasis.hmac field thread-safe or document single-threaded init requirement (
  core/.../encryption/SecretBasis.kt)
- [ ] (Claude) Unify Task/ScheduledTask/StartupTask timeout defaults (currently 30s in interface, 5min in factories,
  should all be 5 minutes)

## 🔵 DESIGN & MAINTENANCE (Priority 13-16)

- [ ] (Claude) Implement circular dependency detection in StartupTask dependency graphs (
  core/.../definition/StartupTask.kt)
- [ ] Review and fix MediaPreviewOptions scaling logic when both needsRatio and needsScaling are true (
  media/.../MediaPreviewOptions.kt:131)

## 🔵 EXISTING TODOS

- [ ] CORS per-domain settings
- [ ] Prepare for secret rotation in the secretBasis setting
- [ ] Rate limiting property on all websockets and HTTP endpoints

---

## 📋 API IMPROVEMENTS BY MODULE

### auth module

- [ ] auth-shared/.../Scope.kt: Add validation for scope strings to prevent malformed scopes
- [ ] auth/.../AuthEndpoints.kt: Consider supporting multiple simultaneous sessions per user
- [ ] auth/.../AuthEndpoints.kt: Add audit logging for authentication events (login, logout, failed attempts)
- [ ] auth/.../AuthEndpoints.kt: Consider adding a "remember me" option for extended session duration
- [ ] auth/.../AuthInfo.kt: Consider adding a method to revoke all sessions for a user
- [ ] auth/.../AuthInfo.kt: Add role-based access control (RBAC) helpers beyond simple hasPermission

### sessions module

- [ ] sessions-oauth/.../OauthProofEndpoints.kt: Use URL builder utility instead of manual query parameter encoding
- [ ] sessions-oauth/.../OauthProofEndpoints.kt: Document that continueUiAuthUrl should NOT include trailing '?' or
  existing query params
- [ ] sessions-oauth/.../OauthProofEndpoints.kt: Add error handling for when profile.email is null with specific error
  messages
- [ ] sessions-oauth/.../OauthProofEndpoints.kt: Document purpose of 'backend' query parameter or remove if unused
- [ ] sessions-oauth/.../OauthProofEndpoints.kt: Add telemetry/metrics for OAuth login attempts, successes, and failures
- [ ] sessions-oauth/.../OauthProofEndpoints.kt: Validate UUID state parameter in callback
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Collapse consecutive delimiters in pathName/identifierName
  transformations
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Make 'all' list immutable (List instead of ArrayList)
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Document email verification guarantees across different OAuth
  providers
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Add validate() method to check required configuration is present
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Consider if GitHub provider needs two API calls or can be optimized
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Expose HTTP client configuration (timeouts, retries)
- [ ] sessions-oauth/.../oauth/OauthProviderInfo.kt: Handle expired/revoked refresh tokens with specific error handling
- [ ] sessions/.../SessionAuthEndpoints.kt: Add configurable session expiration policies
- [ ] sessions/.../SessionAuthEndpoints.kt: Support session refresh without full re-authentication
- [ ] sessions/.../SessionAuthEndpoints.kt: Add device/location tracking for sessions
- [ ] sessions/.../SessionAuthEndpoints.kt: Add session invalidation on password change

### engine modules

- [ ] engine-netty/.../NettyEngine.kt: Extract magic numbers to named constants (120 seconds idle timeout, buffer sizes)
- [ ] engine-netty/.../NettyEngine.kt: Fix toLightningHeaders() to handle Set-Cookie and other headers that shouldn't be
  split
- [ ] engine-netty/.../NettyEngine.kt: Add metrics/telemetry for request/response timing, error rates, concurrent
  connections
- [ ] engine-netty/.../NettyEngine.kt: Add streaming support for large responses instead of loading entire body into
  memory (line 489)
- [ ] engine-netty/.../NettyEngine.kt: Make idle timeout configurable via NettyRuntimeSettings
- [ ] engine-netty/.../NettyEngine.kt: Add logging/metrics for WebSocket failures (line 387, 413)
- [ ] engine-netty/.../NettyEngine.kt: Document thread safety characteristics of currentState in
  LocalWebSocketConnection
- [ ] engine-netty/.../NettyEngine.kt: Remove unused TypeRetriever class if not referenced elsewhere
- [ ] engine-netty/.../NettyRuntimeSettings.kt: Validate workerThreads is positive when non-null
- [ ] engine-netty/.../NettyRuntimeSettings.kt: Add separate settings for boss thread count (currently hardcoded to 1)
- [ ] engine-netty/.../NettyRuntimeSettings.kt: Use Int directly for backlog instead of DataSize converted to int
- [ ] engine-netty/.../NettyRuntimeSettings.kt: Document when to adjust recvBufBytes/sendBufBytes for performance tuning
- [ ] engine-netty/.../NettyRuntimeSettings.kt: Add idle timeout configuration
- [ ] engine-ktor/.../extensions.kt: Complete MultiPart support implementation or remove commented code
- [ ] engine-ktor/.../extensions.kt: Fix Headers.adapt() to handle Set-Cookie properly (shouldn't split comma-separated
  values)
- [ ] engine-ktor/.../extensions.kt: Add error handling for invalid content types in adapt()
- [ ] engine-ktor/.../extensions.kt: Fix typo "MutliPart" -> "MultiPart" in comments
- [ ] engine-ktor/.../KtorEngine.kt: Document that start() uses runBlocking or provide suspending alternative
- [ ] engine-ktor/.../KtorEngine.kt: Consider adding graceful shutdown support
- [ ] engine-ktor/.../KtorEngine.kt: Add configuration for connection pool settings
- [ ] engine-ktor/.../KtorEngine.kt: Document thread pool behavior and configuration
- [ ] engine-jdk-server/.../JdkEngine.kt: Add request/response logging middleware
- [ ] engine-jdk-server/.../JdkEngine.kt: Add support for HTTP/2
- [ ] engine-jdk-server/.../JdkEngine.kt: Consider adding metrics for request processing time
- [ ] engine-local/.../LocalEngine.kt: Add request timing metrics for test performance analysis
- [ ] engine-local/.../LocalEngine.kt: Support simulating network conditions (latency, failures) for testing
- [ ] engine-aws-serverless: Document Lambda cold start optimization strategies
- [ ] engine-aws-serverless: Add support for Lambda provisioned concurrency configuration

### core module

- [ ] core/.../encryption/Signer.kt: Add helper methods for RSA signers similar to ECDSA helpers (ES256, ES384, ES512)
- [ ] core/.../encryption/Signer.kt: Make verify() return Result type or throw exceptions on signature verification
  failure
- [ ] core/.../encryption/SecretBasis.ciphers.kt: Consider using AES-GCM instead of AES-CBC for authenticated
  encryption (multiple locations)
- [ ] core/.../encryption/SecretBasis.ciphers.kt: Make cipher naming consistent (cipher() vs AES_GCM() patterns)
- [ ] core/.../exceptions.kt: Add more common HTTP status exceptions (e.g., ConflictException for 409, GoneException for
  410)
- [ ] core/.../exceptions.kt: Add builder-style methods for adding headers to exceptions
- [ ] core/.../exceptions.kt: Document LSError as public type if it isn't already
- [ ] core/.../AnonType.kt: Make value() method throw more descriptive exception when deserialization fails
- [ ] core/.../AnonType.kt: Add convenience factory method for creating AnonType instances
- [ ] core/.../shortcuts.kt: Document that pathRedirect is older, less precise version - prefer pathMoved
- [ ] core/.../http/HttpEndpoint.kt: Add extension properties for less common HTTP methods (PATCH, OPTIONS, etc.)
- [ ] core/.../data/SerializableCache.kt: Add remove() method to explicitly invalidate cache entries
- [ ] core/.../data/SerializableCache.kt: Optimize hash code computation (currently computes on every ByteArray access)
- [ ] core/.../data/Expiring.kt: Add timeRemaining property to check how much time left before expiration
- [ ] core/.../data/KFile.ext.kt: Add path validation/normalization for edge cases (empty paths, "../" traversal, etc.)
- [ ] core/.../data/Request.kt: Add convenience accessors for common operations
- [ ] core/.../definition/Task.kt: Add support for task cancellation
- [ ] core/.../definition/Task.kt: Add task progress reporting mechanism
- [ ] core/.../definition/ScheduledTask.kt: Support cron-like expressions for complex schedules
- [ ] core/.../definition/ScheduledTask.kt: Add distributed locking to prevent duplicate execution in multi-instance
  deployments
- [ ] core/.../definition/StartupTask.kt: Add retry logic for failed startup tasks
- [ ] core/.../definition/StartupTask.kt: Support parallel execution of independent startup tasks
- [ ] core/.../http/HttpContent.kt: Add streaming support for large file uploads/downloads
- [ ] core/.../http/HttpRequest.kt: Add method to parse Authorization header into typed structure
- [ ] core/.../http/HttpResponse.kt: Add builder pattern for constructing complex responses
- [ ] core/.../settings/ServerSettings.kt: Add environment-specific settings override mechanism
- [ ] core/.../settings/ServerSettings.kt: Support encrypted settings values for sensitive configuration
- [ ] core/.../serialization: Add support for more serialization formats (XML, Protobuf)
- [ ] core/.../terraform: Add support for more cloud providers (Azure, GCP)

### typed module

- [ ] typed-shared/.../models.kt: Add cursor-based pagination support in addition to skip/limit
- [ ] typed-shared/.../models.kt: Add timestamps (createdAt, updatedAt) to QueryResult
- [ ] typed-shared/.../models.kt: Use stronger type than raw String for futureCallToken
- [ ] typed-shared/.../models.kt: Add batch operation result type that includes partial success info
- [ ] typed-shared/.../Fetcher.kt: Add request/response interceptor support for logging and metrics
- [ ] typed-shared/.../Fetcher.kt: Add configurable timeouts per request
- [ ] typed-shared/.../Fetcher.kt: Add retry logic with exponential backoff
- [ ] typed-shared/.../Fetcher.kt: Support request cancellation
- [ ] typed-shared/.../ClientWebSocket.kt: Use enum or constants for close codes instead of Short
- [ ] typed-shared/.../ClientWebSocket.kt: Add automatic reconnection support with exponential backoff
- [ ] typed-shared/.../ClientWebSocket.kt: Add Flow-based API alongside callbacks
- [ ] typed-shared/.../ClientWebSocket.kt: Add ping/pong support for connection health monitoring
- [ ] typed-shared/.../ClientModelRestEndpoints.kt: Add optimistic locking support (ETags or version fields)
- [ ] typed-shared/.../ClientModelRestEndpoints.kt: Support batch operations with partial success results
- [ ] typed-shared/.../ClientModelRestEndpoints.kt: Add transaction support for atomic bulk operations
- [ ] typed-shared/.../ClientModelRestEndpoints.kt: Add watch() method for long-polling alternative to WebSocket
- [ ] typed/.../ApiHttpHandler.kt: Add rate limiting at endpoint level
- [ ] typed/.../ApiHttpHandler.kt: Add cache header support (ETag, Last-Modified)
- [ ] typed/.../ApiHttpHandler.kt: Add distributed tracing with correlation IDs
- [ ] typed/.../ApiHttpHandler.kt: Add deprecation annotation support for generated SDKs
- [ ] typed/.../ApiHttpHandler.kt: Include response examples for each error case in documentation
- [ ] typed/.../ModelRestEndpoints.kt: Add soft delete support
- [ ] typed/.../ModelRestEndpoints.kt: Add field projection to return only requested fields
- [ ] typed/.../ModelRestEndpoints.kt: Add aggregation endpoints (count, sum, avg, etc.)

### database module

- [ ] database: Complete Postgres implementation (currently partial)
- [ ] database: Add connection pool monitoring and metrics
- [ ] database: Add query performance logging and slow query detection
- [ ] database: Support database migrations/schema versioning
- [ ] database: Add read replica support for scaling reads
- [ ] database: Add transaction isolation level configuration
- [ ] database: Support database sharding for horizontal scaling

### cache module

- [ ] cache: Add cache statistics (hit rate, miss rate, eviction count)
- [ ] cache: Add cache warming strategies on startup
- [ ] cache: Support cache tags for bulk invalidation
- [ ] cache: Add distributed cache invalidation across multiple instances
- [ ] cache: Support multiple cache backends simultaneously (L1/L2 cache)

### files module

- [ ] files-shared/.../models.kt: Use stronger type than raw String for futureCallToken
- [ ] files: Add image optimization on upload (resize, compress)
- [ ] files: Add virus scanning integration
- [ ] files: Add support for resumable uploads
- [ ] files: Add CDN integration configuration
- [ ] files: Add file retention policies (auto-delete after X days)
- [ ] files: Add support for pre-signed URLs with custom expiration

### media module

- [ ] media: Add support for video transcoding
- [ ] media: Add thumbnail generation for videos
- [ ] media: Add support for animated GIF optimization
- [ ] media: Add watermarking support
- [ ] media: Add facial recognition/detection capabilities
- [ ] media: Add EXIF data extraction and manipulation

### email module

- [ ] email: Add email template system with variable substitution
- [ ] email: Add support for email attachments
- [ ] email: Add bounce handling and tracking
- [ ] email: Add email open/click tracking
- [ ] email: Add unsubscribe link management
- [ ] email: Support batch email sending with rate limiting

### sms module

- [ ] sms: Add support for multiple SMS providers with failover
- [ ] sms: Add SMS delivery status tracking
- [ ] sms: Add support for MMS (multimedia messages)
- [ ] sms: Add two-way SMS support (receiving messages)

### documentation

- [ ] docs: Add architecture decision records (ADRs)
- [ ] docs: Add deployment guides for each engine
- [ ] docs: Add performance tuning guide
- [ ] docs: Add security best practices guide
- [ ] docs: Add migration guide between versions
- [ ] docs: Add cookbook with common patterns and recipes
- [ ] docs: Add video tutorials for getting started

### testing

- [ ] Add integration tests for all service abstractions
- [ ] Add performance benchmarks for critical paths
- [ ] Add load testing examples and guidelines
- [ ] Add security testing (OWASP Top 10)
- [ ] Add chaos engineering tests for resilience
- [ ] Add contract tests for API backward compatibility

### build & tooling

- [ ] Add code coverage reporting
- [ ] Add static analysis tools (detekt, ktlint)
- [ ] Add dependency vulnerability scanning
- [ ] Add automated dependency updates
- [ ] Add Docker images for demo and examples
- [ ] Add GitHub Actions CI/CD examples

---

## 📊 Summary

**Total Tasks:** 200+
**Critical Security:** 2
**High Priority NPE:** 4
**Medium Priority Parsing:** 6
**Thread Safety:** 3
**Design & Maintenance:** 4
**Existing TODOs:** 2
**API Improvements:** 180+

**Legend:**

- 🔴 Critical Security - Fix immediately
- 🟡 High Priority - Fix soon
- 🟠 Medium Priority - Schedule in next sprint
- 🟢 Low Priority - Backlog
- 🔵 Design - Consider for major version
- 📋 API - Enhancement suggestions

---

*This TODO list is generated from SECURITY_AND_QUALITY_ISSUES.md and API suggestions extracted from all Kotlin files in
the codebase. API improvements are suggestions for future enhancements and do not indicate critical problems.*
