# Security Audit: Sessions Modules

**Date**: 2025-11-06
**Auditor**: Claude Code (Security Review)
**Scope**: sessions, sessions-email, sessions-sms, sessions-shared modules

## Executive Summary

This security audit identified **7 security issues** across the Lightning Server sessions modules. The issues range from information disclosure to authentication bypass vulnerabilities. All issues have been documented inline with `TODO: Security issue:` comments in the affected files.

### Severity Breakdown
- **High**: 3 issues (TOTP code reuse, WebAuthN challenge reuse, JWT algorithm confusion)
- **Medium**: 2 issues (Sign count validation missing, Backup code permissions)
- **Low**: 2 issues (PIN attempt count disclosure, WebAuthN removal failure handling)

## Critical Findings

### 1. TOTP Code Reuse Vulnerability (HIGH)
**File**: `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/TimeBasedOTPProofEndpoints.kt:170-174`

**Issue**: TOTP codes can be reused multiple times within their validity window (typically 90 seconds). The `generator.isValid()` accepts codes within ±1 time window, but used codes are never tracked.

**Impact**: An attacker who intercepts a valid TOTP code (via shoulder surfing, MitM, etc.) can reuse it multiple times during the validity period.

**Recommendation**:
```kotlin
// Before validation, check if code was already used
val codeKey = "totp-used-${matching._id}-${input.password}"
if (cache().get<Boolean>(codeKey) == true) {
    throw BadRequestException("Code already used")
}

// After successful validation, mark as used
cache().set(codeKey, true, 2 * matching.period.seconds)
```

### 2. JWT Algorithm Confusion Attack (HIGH)
**File**: `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/token/JwtTokenFormat.kt:108-111`

**Issue**: The JWT header algorithm (`alg`) is decoded but never validated against the expected algorithm. An attacker could:
- Change `"alg": "HS256"` to `"alg": "none"` (bypass signature)
- Switch between HMAC and RSA algorithms
- Downgrade to weaker algorithms

**Impact**: Complete authentication bypass if "none" algorithm is accepted, or key confusion attacks if algorithm can be switched.

**Recommendation**:
```kotlin
val header: JwtHeader = server.internalSerialization.json.decodeFromString(...)
if (header.alg != this.name) {
    throw TokenException("Algorithm mismatch: expected ${this.name}, got ${header.alg}")
}
```

### 3. WebAuthN Challenge Reuse (HIGH)
**File**: `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/WebAuthNProofEndpoints.kt:239-243, 402-404`

**Issue**: WebAuthN challenges are removed from cache after retrieval, but if `cache().remove()` throws an exception, the challenge remains valid and could be reused for replay attacks.

**Impact**: An attacker who intercepts a WebAuthN authentication response could replay it if the cache removal fails.

**Recommendation**:
```kotlin
// Mark as used BEFORE validation begins
cache().set(cacheKey + "-used", true, expiration)
val fromCache = cache().get<RegistrationCache>(cacheKey)
    ?: throw BadRequestException("No Challenge available")

// Verify not already used
if (cache().get<Boolean>(cacheKey + "-used") != true) {
    throw BadRequestException("Challenge expired or already used")
}
```

## High-Priority Findings

### 4. WebAuthN Sign Count Not Validated (MEDIUM)
**File**: `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/WebAuthNProofEndpoints.kt:450-453`

**Issue**: The authenticator's sign count is updated but never checked for rollback. A decreasing sign count indicates credential cloning, a critical security event in WebAuthN.

**Impact**: Attackers who clone a hardware authenticator can use the cloned device without detection.

**Recommendation**:
```kotlin
val newSignCount = authData.authenticatorData?.signCount ?: 0L
if (newSignCount > 0 && newSignCount < publicKeyCredential.lastSignCount) {
    // Log security event
    throw SecurityException("Sign count rollback detected - possible credential cloning")
}
```

### 5. Backup Code Permissions Too Open (MEDIUM)
**File**: `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/BackupCodeEndpoints.kt:79-82`

**Issue**: The `modelInfo` uses `noAuth` with default (wide-open) `ModelPermissions`. While this may be intentional for the prove endpoint, the table is also exposed via a REST endpoint that could allow unauthorized access.

**Impact**: Potential for unauthorized reading or modification of backup codes if the REST endpoint is exposed.

**Recommendation**: Either:
1. Remove the `rest` endpoint exposure, or
2. Add proper authentication and field masking to `modelInfo`

## Lower-Priority Findings

### 6. PIN Error Message Information Leakage (LOW)
**File**: `sessions/src/main/kotlin/com/lightningkite/lightningserver/sessions/proofs/PinHandler.kt:67-70`

**Issue**: Error message reveals remaining attempt count: `"Incorrect PIN. ${maxAttempts - attempts} attempts remain."`

**Impact**: Helps attackers optimize brute-force strategy by knowing exactly how many attempts remain before lockout.

**Recommendation**: Use generic message without revealing attempt count:
```kotlin
throw BadRequestException(
    detail = "pin-incorrect",
    message = "Incorrect PIN. Please try again."
)
```

### 7. WebAuthN Cache Removal Exception Handling (LOW)
**File**: Same as issue #3

**Issue**: If cache operations fail between validation steps, the system may be in an inconsistent state.

**Impact**: Minor - could lead to inconsistent state if cache is unreliable.

**Recommendation**: Use try-finally blocks or transaction-like semantics for cache operations.

## Positive Security Observations

The following security practices were correctly implemented:

✅ **Password Hashing**: Uses `secureHash()` with proper algorithms (bcrypt/argon2)
✅ **Constant-Time Comparison**: Uses `checkAgainstHash()` to prevent timing attacks
✅ **Rate Limiting**: Properly implemented via `constrainAttemptRate()`
✅ **Cryptographic Signatures**: Proofs are properly signed with all relevant fields
✅ **Bad Word Filtering**: PIN and backup code generation avoids offensive combinations
✅ **Secure Random**: Uses `SecureRandom` for secret generation
✅ **Hash Secrecy**: Sensitive hashes are masked in API responses
✅ **Single-Use Enforcement**: Backup codes are deleted after use
✅ **Challenge Expiration**: All challenges have proper expiration times
✅ **WebAuthn Library**: Uses well-tested webauthn4j library for FIDO2 validation

## Implementation Priorities

1. **Immediate** (Before production use):
   - Fix JWT algorithm confusion (#2)
   - Fix TOTP code reuse (#1)
   - Fix WebAuthN challenge reuse (#3)

2. **High Priority** (Next release):
   - Add WebAuthN sign count validation (#4)
   - Review backup code permissions (#5)

3. **Medium Priority** (Future improvements):
   - Remove PIN attempt count from error messages (#6)
   - Improve cache operation error handling (#7)

## Testing Recommendations

Security-focused test cases should be added for:

1. **TOTP**: Verify same code cannot be used twice
2. **JWT**: Verify algorithm switching is rejected
3. **WebAuthN**: Verify challenge replay is rejected
4. **WebAuthN**: Verify sign count rollback triggers error
5. **Rate Limiting**: Verify lockout behavior under high attempt rates
6. **Timing Attacks**: Verify constant-time comparison in all password/hash checks

## Conclusion

The sessions module demonstrates strong security fundamentals with proper cryptography, rate limiting, and authentication flows. However, the identified issues—particularly TOTP code reuse, JWT algorithm confusion, and WebAuthN challenge handling—should be addressed before production deployment to prevent authentication bypass attacks.

All issues have been documented inline with `TODO: Security issue:` comments for easy reference during remediation.
