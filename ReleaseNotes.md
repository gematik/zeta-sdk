<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# Release Notes ZETA SDK

## Version: v0.5.1

This version implements the ZETA protocol for the ZETA client SDK.

It provides SDK bindings for kotlin (as original implementation), Java, and C++.

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
- C++ client build

### Known issues:

#### Functional

## Changes from 0.5.0

- Update versions of kotlin, gradle, netty-codec, and others

## Changes from 0.4.2

- Fix TLS Cipher suite validation for TLS 1.2. OkHttp returns IANA names but validator was comparing against OpenSSL names,
- causing all TLS 1.2 connections to fail compliance check.
- Update aus gemspec_ZETA RC 26_1
  - key thumbprints in SubjectToken

## Changes from 0.4.1

- Refactored C++ client: Moved C++ API types and bindings from the example client into the SDK itself The SDK now ships a single header file (`libzeta_sdk_api.h` / `zeta_sdk_api.h`) with full HTTP CRUD and WebSocket STOMP support
- Added standalone C++ native client (`zeta-nativeclient-cpp`) with Makefile for cross-platform builds (macOS, Linux, Windows) without requiring Gradle
- Added all HTTP methods to JVM `HttpClientExtension`: `putAsync`, `patchAsync`, `deleteAsync`, `headAsync`, `optionsAsync`

## Changes from 0.4.0

- C++ API now has length restrictions in the strings
- removed dynamic linking in C++ client
- disable TLS validation configurable in C++ client

## Changes from 0.3.1

- Hinzufügen fehlender dependency Check results zu den reporting Artefakten
- Triggern der Testsuite bei jedem Commit auf einem Merge-Request
- Build-Prozess für den C++ Client in einem Docker Container ermöglicht
- Härtung des TLS durch Einschränkung der cipher suites
- Erweiterung des Testdriver durch ein "load" Interface, um mehrere Client-Instanzen parallel steuern zu können für den Lasttest
  - Dazu die Möglichkeit, das SMC-B Zertifikat als base64-encoded String direkt als Konfiguration zu nutzen
- Fixes bei Headern
  - Forwarded-Header werden im inneren ASL Request herausgefiltert
- Anpassungen am Attestation service, Vorbereitungen für Windows
  - hier werden aktuell noch Spezifikationsänderungen erwartet
- Erweiterungen einiger Logs um Zeitmessungen
- Fix websocket communication in Java client

## Changes from 0.3.0

- Fixed demo client to enable functionalities (add, edit, delete) when the attestation state is unknown

## Changes from 0.2.12

- Attestation service for Linux (TPM based)
- Increase code coverage for modules Crypto and ASL
- Fixes included:
  - Dpop token htu value for request with ASL
  - Proper configuration handling, when PDP and PEP are on different hosts
  - Improvement for reading registration number from SM-B certificate
  - Handling of X-Forwarded headers when using ASL

## Changes from 0.2.11

- Fix websockets on C++ client

- Work on the Client Attestation Service
  - Integrate tpm2-tss library bindings to access TPM (only linux)
  - Implement TPM commands

## Changes from 0.2.10 (internal)

- Add missing copyright header

## Changes from 0.2.9

- Update of release Notes

## Changes from 0.2.8

- Rollback of the netty version due to intermittent errors in the test framework

## Changes from 0.2.7

- Filtering of the included ktor-client-curl library

## Changes from 0.2.6

- Significant adjustments to the C++ client through integration of ktor-client-curl for updated OpenSSL version with support for post-quantum cryptography
- Implemented cryptographic functions for desktop clients using OpenSSL
- ASL debug mode implemented, including new "ASL_PROD" configuration for implemented clients
- Improvements for ASL error handling
- Version updates

## Changes from 0.2.5

- Send clientId and clientIdIssuedAt within client assessment data for token exchange (websockets)

## Changes from 0.2.4

- Correct the field platform name

## Changes from 0.2.3

- fix for asl debug header
- fix for web sockets
- fix for sending client-/user-data

## Changes from 0.2.2

- fix for Host header

## Changes from 0.2.1

- minor bug fixes
- Version updates

## Release 0.1.2

### added:
- Prototype of the ZETA SDK added
