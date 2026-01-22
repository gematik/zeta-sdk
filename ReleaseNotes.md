<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# Release Notes ZETA SDK

## Release: 0.2.10

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

- C++ API is implemented but websockets are not yet functional

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
