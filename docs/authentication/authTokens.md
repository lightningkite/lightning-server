## Tokens

There are two types of tokens used in Lightning Server Authentication.

- Refresh Token
- Access Token


### Refresh Token
Successful authentication with result in a Refresh Token. This will look something like

`
    "refresh/User/00000000-0000-0000-0000-000000000000:asdfasfdasfdasdfasdfasdfasdf"
`

Let's break down the parts of the token.

- refresh - Indicates the type of token. This is a Refresh Token
- User - Indicates the Subject type that is authenticated
- 00000000-0000-0000-0000-000000000000 - Will be the _id of the session
- asdfasfdasfdasdfasdfasdfasdf - The session secret. This is a randomly generated plain text string related to the
  session. When validating the Refresh Token, the server will securely hash this secret and compare against a previously
  hashed value stored with the session in the database.

You can use the Refresh Token for all your authenticated requests, however this is not recommended. Using the Refresh
Token is very slow and expensive. The validation process includes: retrieving the session, hashing the secret for 
comparison, and updating the sessions lastUsed, userAgents, and ips.

Instead, it is recommended to use the Refresh Token to retrieve a short-lived Access Token and use it for your requests.

You can create an Access Token by querying the endpoint "token/simple" in your AuthEndpointsForSubject instance.

### Access Token

Lightning Server supports 3 different types of Access Tokens.

- JwtToken
- PublicTinyToken
- PrivateTinyToken

The `JwtTokenFormat` is a Standard implementation of the JsonWebToken. JWT are a common authentication method all over
the internet. You can read up on JWTs [here](https://auth0.com/docs/secure/tokens/json-web-tokens).

The `PublicTinyTokenFormat` and `PrivateTinyTokenFormat` are both custom token implementation created for Lightning 
Server. They both use JavaData instead of json for serialization, then Encoded with Base64. This results in a much
smaller token. The difference between the two is the Public Token will attach a signature to the end of the data, while 
the Private Token will encrypt the data.

Access tokens support caching information about the User, Permissions, or anything else you wish. 

Due to the nature of access token, they are hard to revoke after created, Which is why you MUST have a short expiration 
in your Access Tokens. Usually between 5-15 minutes.
