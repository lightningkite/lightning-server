package com.lightningkite.lightningserver.auth

/*

Internal Auth

Auth methods all have their own endpoints, just like they do now.
Difference is that they will auth to their respective type, and that auth can be converted into a real user object.

KeyID -> Password -> KeyID
    establish(key, value)
    auth(key, value)

KeyID -> OTP -> KeyID
    establish(key): secret
    auth(key, value)

OAuth -> Email
    open(key?): url

Email -> Email
    request(key)
    auth(key, value)

SMS -> Phone
    request(key)
    auth(key, value)

Auth for Thing
    request(auth proofs from above, ip): Token with auth level
    otherAuthMethods(token with lowest auth level): methods with keys

Sessions / API Keys
    start(auth token, scopes): session
    list(): sessions
    token(auth session): token


Password + OTP Auth Sample
- password.auth
- thing.request
- thing.otherAuthMethods
---
- otp.auth
- thing.request
- session.start



 */