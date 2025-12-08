# OAuth Package

This package contains OAuth 2.0 models for implementing OAuth authorization server functionality.

## Files

### models.kt
Complete OAuth 2.0 data models per RFC 6749:

**Client Management:**
- **OauthClient** - Registered OAuth client application with credentials and configuration
- **OauthClientSecret** - Client secret with hashing and rotation support

**Authorization Flow:**
- **OauthCodeRequest** - Authorization endpoint request to initiate OAuth flow
- **OauthCode** - Authorization endpoint response with code or error
- **OauthTokenRequest** - Token endpoint request to exchange code/refresh for tokens
- **OauthResponse** - Token endpoint response with access/refresh/ID tokens

**Configuration Enums:**
- **OauthPromptType** - User interaction control (consent, select_account, none)
- **OauthResponseMode** - How authorization response is returned (form_post, query)
- **OauthAccessType** - Refresh token control for offline access (online, offline)

**Constants:**
- **OauthGrantTypes** - Grant type constants (authorization_code, refresh_token)

All models include security considerations for secrets, state parameters, redirect URIs, and token lifetimes.
