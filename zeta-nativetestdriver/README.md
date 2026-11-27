# Zeta Native Test Driver

Kotlin/Native HTTP server that exposes the ZETA SDK on port `8091` for testing.

## Build

```bash
# macOS (local dev)
./gradlew :zeta-nativetestdriver:linkReleaseExecutableMacosArm64

# Linux (CI / Docker)
./gradlew :zeta-nativetestdriver:linkReleaseExecutableLinuxX64
```

## Run

```bash
export FACHDIENST_URL=https://...
export SMB_KEYSTORE_FILE=/path/to/keystore.p12
export SMB_KEYSTORE_ALIAS=alias
export SMB_KEYSTORE_PASSWORD=00
export ASL_PROD=false

# PoPP token (optional). Static fallback:
export POPP_TOKEN=<token>
# Generated at startup from the test token generator (the other three default to the values below):
export POPP_TOKEN_GENERATOR_URL=https://...
export POPP_PROOF_METHOD=ehc-practitioner-user-x509
export POPP_PATIENT_ID=X110639491
export POPP_INSURER_ID=109500969

# macOS
./zeta-nativetestdriver/build/bin/macosArm64/releaseExecutable/native-server.kexe

# Linux
./zeta-nativetestdriver/build/bin/linuxX64/releaseExecutable/native-server.kexe
```

The PoPP token is fetched once at startup (actor data is read from the `SMB_KEYSTORE_*` SM(C)-B certificate);
if generation fails, a warning is logged and `POPP_TOKEN` is used. On the native driver the generator call needs `DISABLE_SERVER_VALIDATION=true`, otherwise it fails (TLS validation is not available there) and `POPP_TOKEN` is used. An incoming `PoPP` header always wins.

## Endpoints

| Endpoint                        | Method | Description                          |
|---------------------------------|--------|--------------------------------------|
| `/nativedriver-api/health`      | GET    | Health check                         |
| `/nativedriver-api/configure`   | POST   | Configure Fachdienst URL and CA cert |
| `/nativedriver-api/hellonative` | GET    | Calls Fachdienst via SDK/ASL         |

## Configure

Before running tests, configure the driver with the target Fachdienst and optional CA:

```bash
curl -X POST http://localhost:8090/nativedriver-api/configure \
  -H "Content-Type: application/json" \
  -d '{
    "resource": "https://zeta-staging.spree.de",
    "caCertificatePem": "-----BEGIN CERTIFICATE-----\nMII...\n-----END CERTIFICATE-----",
    "disableTlsVerification": false
  }'
```

| Field                    | Required | Description                         |
|--------------------------|----------|-------------------------------------|
| `resource`               | yes      | Fachdienst base URL                 |
| `caCertificatePem`       | yes      | Custom CA certificate in PEM format |
| `disableTlsVerification` | yes      | Skip TLS verification               |

## Test

```bash
# Health check
curl http://localhost:8090/nativedriver-api/health

# Call Fachdienst
curl http://localhost:8090/nativedriver-api/hellonative

# With PoPP token
curl -H "PoPP: <token>" http://localhost:8090/nativedriver-api/hellonative
```
