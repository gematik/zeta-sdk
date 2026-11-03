# Client Registration Component

Registers the client instance at the guard's authorization server via Dynamic Client Registration (DCR, RFC 7591).
The result is a `client_id` that all later token requests use.

## Design

- **ClientRegistrationApi / ClientRegistrationApiImpl**: Sends `POST {registration_endpoint}` with the registration JSON and parses the response. Status handling: `201` -> success, `400`/`401`/`403`/`409`/other -> `ClientRegistrationException`. `fetchNonce` gets a nonce from `GET {nonce_endpoint}` and returns it unchanged.
- **ClientRegistrationRequest**: client metadata (`token_endpoint_auth_method`, `grant_types`, `client_name`, `redirect_uris`, `jwks`) plus the attestation part of one registration path: software (`nonce`, `signed_hash_puk_client_sig`), Apple, Android or ZETA attestation token. Field names and forms follow gematik's `dcr-request.yaml` 1.2.4, except for the software proof of possession (see below).
- **ClientRegistrationResponse**: Full DCR response, most importantly `client_id`.
- **ClientRegistrationStorage**: Persists the registration per registration endpoint.
- **ClientRegistrationHandler** (in `flow-controller`): Orchestrates when registration runs.

## How it works ?

1. `ClientRegistrationHandler` handles `FlowNeed.ClientRegistration` (triggered by `ZetaSdkClient.register()` or automatically before authentication).
2. It loads the client instance public key from the TPM (`getOrGenerateClientInstancePublicKey`).
3. If a cached registration exists for this registration endpoint **and** was made with the current key (`kid` check), nothing happens. If the TPM key changed, the client re-registers.
4. Otherwise it builds the request:
   * `token_endpoint_auth_method = private_key_jwt`
   * `grant_types`: `[authorization_code, urn:ietf:params:oauth:grant-type:token-exchange, refresh_token]` for mobile clients (OIDC login), `[urn:ietf:params:oauth:grant-type:token-exchange, refresh_token]` for institution clients (SM-B/SMC-B), the schema's forms (1) and (3)
   * `redirect_uris`: the two OIDC callback URIs (`redirectUri`, `oidcRedirectUri` of the `OidcConfig`) when an `OidcTokenProvider` is configured, left out otherwise
   * `jwks`: the client instance key
   * for software attestation, a proof of possession bound to a fresh nonce from `nonce_endpoint`: `signed_hash_puk_client_sig` is the client instance key's `SHA256withECDSA` signature (DER, base64url) over `0x04 || X || Y || nonce`, the nonce base64url-decoded. That is the form the deployed guard checks (keycloak-zeta `toClientHashData`). `dcr-request.yaml` 1.2.4 defines another form, `h = SHA-256(canonical JWK || UTF-8(nonce))` as the message, which the guard rejects for now. Apple, Android and the fast path still sign SHA-256 of the raw key point (`0x04 || X || Y`) without a nonce, which is what the guard checks today.
5. On failure it retries up to 3 times with increasing delay — except on `202`, `403` (denied) and `409` (already registered), and for attested requests also `400` and `401`, which fail immediately. Every software attempt fetches a new nonce, because the guard uses up a nonce even when it rejects the request.
6. The response is stored together with the client key in `ClientRegistrationStorage`.

## Usage

Consumers don't call this API directly. Registration runs automatically:

```kotlin
val sdk = ZetaSdk.build(resource, buildConfig)
sdk.register()      // explicit, or implicitly via sdk.authenticate()
```

Registration establishes only the client instance key (F2) plus, on mobile, the hardware attestation.
Software registration is used only when the device has no hardware support (no Secure Enclave client
key or no App Attest); a `400` on an attested registration fails the registration and discards the App
Attest key, it does not fall back to software.
It carries no email address and is never answered with `202`; the Guard replies `201 Created` with a
`status` as the Guard reported it at registration time (`pending_user_binding` for a fresh mobile registration, `bound` on the fast path); the SDK persists it for diagnostics and does not act on it. The email (F1) is bound
after the OIDC user authentication — see [authentication/README.md](../authentication/README.md). The
response carries no Registration Access Token; client management is authorised with the client
instance key (`private_key_jwt`) and DPoP-bound access tokens.
