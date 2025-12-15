# Authentication Component

The Authentication component is responsible for obtaining, refreshing, and providing valid access tokens for accessing protected resources.
It performs three tasks:
1. Decide which token to use (cached, refreshed, or new)
2. Communicate with the Authorization Server (nonce + token request)
3. Create required cryptographic proofs (Subject Token, DPoP)

## Design
- **AccessTokenProviderImpl**: Produces a valid access token when requested.
- **AuthenticationApi**: Handles HTTP requests to the Authorization Server (nonce + token response).
- **SubjectTokenProvider**: Creates a signed subject_token (SMB or SMC-B).
- **AuthenticationStorage**: Stores access_token, refresh_token, and expiration timestamp.

**AccessTokenProviderImpl**

-getValidToken()
Decision logic for obtaining a valid token.

```mermaid
flowchart TD
A[getValidToken] --> B{Cached token valid?}
B -->|Yes| C[Return cached token]
B -->|No| D{Refresh token exists?}
D -->|Yes| E[Try refreshToken]
E -->|Success| F[Return refreshed token]
E -->|Fail| G[issueNewAccessToken]
D -->|No| G
G --> H[Return new access token]
```

-issueNewAccessToken()

Used when no valid token exists. Steps:
1. Fetch nonce
2. Create client assertion (attestation)
3. Create signed subject token (SMB or SMC-B)
4. Create DPoP proof
5. Request new access/refresh token
6. Persist tokens

```mermaid
sequenceDiagram
participant P as AccessTokenProvider
participant A as AuthenticationApi
participant S as SubjectTokenProvider
participant AT as AttestationApi
participant AS as Auth Server
participant ST as Storage

P->>A: fetchNonce()
A-->P: nonce

P->>AT:createClientAssertion()
AT-->>P: client_assertion_jwt

P->>S: createSubjectToken()
S-->>P:subject_token_jwt

P->>P: createDpopToken()

P->>A: requestAccessToken(TokenExchange)
A->>AS:POST /token
AS-->>A: AccessTokenResponse
A-->>P: access_token, refresh_token, exp

P->>ST: save(fqdn, access, refresh, exp)P: client_assertion_jwt
```

refreshToken()

Same pattern, but using grant_type=refresh_token.
Steps:
1. Fetch nonce
2. Create client assertion
3. Create DPoP
4. Request refreshed tokens
5. Persist tokens


**SubjectTokenProvider**

Abstraction for producing the subject_token used during token-exchange.

Two implementations:

SMB
* Loads certificate from local keystore (via TPM)
* Creates JWT and signs it locally

SMC-B
* Reads certificate via Connector API
* Signs token using externalAuthenticate


**AuthenticationApi**
* fetchNonce() -> GET nonce
* requestAccessToken() -> POST form data + DPoP header
* Parses OAuth token response
  No local state; only network calls.

**AuthenticationStorage**
stores by fqdn:
* access_token
* refresh_token
* expiration_timestamp
  Used by getValidToken() to decide whether to reuse, refresh, or obtain a new token
