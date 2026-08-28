# Client Registration Component

Registers the client instance at the guard's authorization server via Dynamic Client Registration (DCR, RFC 7591).
The result is a `client_id` that all later token requests use.

## Design

- **ClientRegistrationApi / ClientRegistrationApiImpl**: Sends `POST {registration_endpoint}` with the registration JSON and parses the response. Status handling: `201` -> success, `400`/`401`/`403`/`409`/other -> `ClientRegistrationException`.
- **ClientRegistrationRequest**: `token_endpoint_auth_method`, `grant_types`, `response_types`, `client_name`, `redirect_uris`, `jwks`.
- **ClientRegistrationResponse**: Full DCR response, most importantly `client_id`.
- **ClientRegistrationStorage**: Persists the registration per registration endpoint.
- **ClientRegistrationHandler** (in `flow-controller`): Orchestrates when registration runs.

## How it works ?

1. `ClientRegistrationHandler` handles `FlowNeed.ClientRegistration` (triggered by `ZetaSdkClient.register()` or automatically before authentication).
2. It loads the client instance public key from the TPM (`getOrGenerateClientInstancePublicKey`).
3. If a cached registration exists for this registration endpoint **and** was made with the current key (`kid` check), nothing happens. If the TPM key changed, the client re-registers.
4. Otherwise it builds the request:
   * `token_endpoint_auth_method = private_key_jwt`
   * `grant_types = [authorization_code, urn:ietf:params:oauth:grant-type:token-exchange, refresh_token]`
   * `response_types = [token, code]`
   * `redirect_uris`: the two OIDC callback URIs (`{requestUri}/app`, `{requestUri}/oidc`) when an `OidcTokenProvider` is configured, empty otherwise
   * `jwks`: the client instance key
5. On failure it retries up to 3 times with increasing delay — except on `403` (denied) and `409` (already registered), which fail immediately.
6. The response is stored together with the client key in `ClientRegistrationStorage`.

## Usage

Consumers don't call this API directly. Registration runs automatically:

```kotlin
val sdk = ZetaSdk.build(resource, buildConfig)
sdk.register()      // explicit, or implicitly via sdk.authenticate()
```

Note: DCR carries no email address. For mobile clients the email is bound during the first
authentication (OTP flow) — see [authentication/README.md](../authentication/README.md).
