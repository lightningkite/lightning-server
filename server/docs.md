# Documentation
Ktor Batteries Server has a lot of nifty features. Here is a brief list of things to get you started.

## Database

## Email
The server can automate sending emails.

- `email.send()` is used to send emails

## Models

- `@Serializable` is used to make a data class serializable

## Server Settings

- `loadSettings()` is used to load an object with server settings into a file

## Tokens
Tokens are used to authenticate http calls across the server.

- `makeToken()` is used to create tokens.
- `checkToken()` is used to validate existing tokens.

You can add additional information to the token using:

- `withSubject()`
- `withClaim()`

These can be used to store information relevant to the token across different routes.
You can parse a token validated with `checkToken()` to retrieve that information using the associated get methods (*e.g. `getClaim()` to access the claim attribute set with `withClaim()`*).
