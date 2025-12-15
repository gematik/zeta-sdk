<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# Release Notes ZETA SDK

## Release 0.2.3

This version implements the "happy flow" for the ZETA protocol for the ZETA client SDK.

Therefore, the API of the clients and the network protocol is stable (with comments see below).
Not all validations are implemented yet and will follow in later releases.


### Included Features:

#### General Protocol:

- Discovery of server parameters via .well-known files
- Software-based Client Attestation
- DPoP token generation
- Client Registration
- SM(C)-B Token generation and use
- Access Token handling
- ASL protocol implementation (messages 1-4 as well as payload encryption/decryption)
- Web Sockets

#### Clients:

- kotlin multiplatform based client and SDK implementation
- testdriver client as container image to use as a proxy for a resource server in test setups
- demo client in kotlin to manually test against the test Fachdienst (resource server)
- Java-client example for how to integrate and use the SDK in a Java application
- C++ client build (but see comments below)

### Known issues:

#### Functional

- Refresh Token handling not yet fully implemented (token rotation)
- C++ API still unstable and crypto-functions missing
- asl tracing header

## Changes from 0.2.2

- fix for Host header

## Changes from 0.2.1

- minor bug fixes
- Version updates

## Release 0.1.2

### added:
- Prototype of the ZETA SDK added
