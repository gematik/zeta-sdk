/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.sdk.flow.handler

import PublicKeyOut
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.DeviceAttestor
import de.gematik.zeta.sdk.attestation.HardwareAttestationType
import de.gematik.zeta.sdk.attestation.RegisteredAttestationType
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApi
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationException
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.clientregistration.model.toJson
import de.gematik.zeta.sdk.crypto.sha256PublicKeyPoint
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.LegacyApiCompat
import de.gematik.zeta.sdk.tpm.ClientKeyPoPSigner
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.http.HttpStatusCode
import joseToDerEcdsa
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class ClientRegistrationHandler(
    private val clientName: String,
    private val regApi: ClientRegistrationApi,
    private val tpmProvider: TpmProvider,
    private val redirectUris: List<String> = emptyList(),
    private val maxRetries: Int = 3,
    private val deviceAttestor: DeviceAttestor? = null,
    private val attestationState: AttestationStateStorage? = null,
    private val popSigner: ClientKeyPoPSigner? = null,
) : CapabilityHandler {
    companion object {
        private const val REGISTRATION_FAILED_ERROR = "REGISTRATION_FAILED_ERROR"

        private val SOFTWARE_NO_RETRY_STATUSES = setOf(202, 403, 409)
        private val ATTESTED_NO_RETRY_STATUSES = SOFTWARE_NO_RETRY_STATUSES + setOf(400, 401)
    }

    private val mutex = Mutex()

    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.ClientRegistration

    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult = mutex.withLock { handleLocked(ctx) }

    private suspend fun handleLocked(ctx: FlowContext): CapabilityResult {
        val authServer = ctx.configurationStorage.getAuthServer()
        checkNotNull(authServer) { "Failed to load authorization server metadata from storage" }

        val registrationKey = authServer.registrationEndpoint ?: authServer.issuer
        val clientKey = tpmProvider.getOrGenerateClientInstancePublicKey()

        val cached = ctx.clientRegistrationStorage.getRegistrationInfo(registrationKey)
        if (!cached?.clientId.isNullOrBlank() && wasRegisteredWith(cached, clientKey.jwk.kid)) {
            return CapabilityResult.Done
        }

        val compat = LegacyApiCompat.select(authServer.apiVersionsSupported)
        Log.i {
            "ZETA API: server offers ${authServer.apiVersionsSupported?.map { it.version } ?: "none (legacy)"}, compat=${compat ?: "none"}"
        }
        val registered = register(authServer.effectiveRegistrationEndpoint, authServer.nonceEndpoint, clientKey, compat)

        val response = registered.response.getOrElse { exception ->
            exception as ClientRegistrationException
            return CapabilityResult.Error(REGISTRATION_FAILED_ERROR, exception.message.toString(), exception.response)
        }

        // Abb-ZETA-DCR-für-mobile-Clients (14) / (21) persist client_id and status together with the client key
        ctx.clientRegistrationStorage.saveRegistration(registrationKey, response.copy(jwks = Jwks(listOf(clientKey.jwk))))
        attestationState?.setAttestationType(registered.type)
        return CapabilityResult.Done
    }

    private class Registered(val response: Result<ClientRegistrationResponse>, val type: RegisteredAttestationType)

    private suspend fun register(
        endpoint: String,
        nonceEndpoint: String,
        clientKey: PublicKeyOut,
        compat: LegacyApiCompat?,
    ): Registered {
        val state = attestationState
        val attestor = deviceAttestor

        when {
            state != null && attestor != null && popSigner != null -> {
                fastPath(endpoint, clientKey, attestor, popSigner, state, compat)?.let { return it }
                hardwarePath(endpoint, clientKey, attestor, popSigner, state, compat)?.let { return it }
            }
            attestor != null && state == null ->
                Log.w { "DeviceAttestor configured without AttestationStateStorage; hardware attestation disabled, using software registration" }
            attestor != null && popSigner == null ->
                Log.w { "DeviceAttestor configured without ClientKeyPoPSigner; hardware attestation disabled, using software registration" }
        }
        Log.i { "Registering with software attestation" }
        return Registered(
            // The guard accepts each nonce once, even when it rejects the request, so every attempt gets a fresh one.
            attemptRegister(endpoint, SOFTWARE_NO_RETRY_STATUSES, compat) { softwareRequest(nonceEndpoint, clientKey) },
            RegisteredAttestationType.SOFTWARE,
        )
    }

    private suspend fun softwareRequest(nonceEndpoint: String, clientKey: PublicKeyOut): ClientRegistrationRequest {
        val nonce = regApi.fetchNonce(nonceEndpoint)
        // signWithClientKey is SHA256withECDSA over its input, which is what the guard verifies; DER on the wire
        val signedHashPukClientSig = joseToDerEcdsa(tpmProvider.signWithClientKey(softwareProofMessage(clientKey.jwk, nonce)))
        return buildSoftwareRegistrationRequest(clientName, clientKey.jwk, nonce, signedHashPukClientSig, redirectUris)
    }

    private suspend fun fastPath(
        endpoint: String,
        clientKey: PublicKeyOut,
        attestor: DeviceAttestor,
        popSigner: ClientKeyPoPSigner,
        state: AttestationStateStorage,
        compat: LegacyApiCompat?,
    ): Registered? {
        val token = state.getZetaAttestationToken() ?: return null
        if (!attestor.hasAttestedKey()) return null
        val request = try {
            val hashPukClientSig = sha256PublicKeyPoint(clientKey.encoded)
            val attestationPop = attestor.proveAttestationKey(hashPukClientSig)
            val signedHashPukClientSig = popSigner.signProofOfPossession()
            buildFastPathRegistrationRequest(clientName, clientKey.jwk, token, signedHashPukClientSig, attestationPop, redirectUris)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(e) { "Fast-path proof failed; using full hardware registration" }
            return null
        }
        Log.i { "Registering via fast-path with ZETA attestation token" }
        val result = attemptRegister(endpoint, ATTESTED_NO_RETRY_STATUSES, compat) { request }
        val status = result.exceptionOrNull()?.let { (it as? ClientRegistrationException)?.response?.status }
        if (status == HttpStatusCode.BadRequest || status == HttpStatusCode.Unauthorized) {
            Log.w { "Fast-path rejected with ${status.value}; dropping ZETA attestation token" }
            state.removeZetaAttestationToken()
            return null
        }
        return Registered(result, attestor.attestationType.toRegistered())
    }

    private suspend fun hardwarePath(
        endpoint: String,
        clientKey: PublicKeyOut,
        attestor: DeviceAttestor,
        popSigner: ClientKeyPoPSigner,
        state: AttestationStateStorage,
        compat: LegacyApiCompat?,
    ): Registered? {
        if (!tpmProvider.isHardwareBacked()) {
            Log.i { "Client instance key is not hardware-backed; using software registration" }
            return null
        }
        if (!attestor.isSupported()) {
            Log.i { "Hardware attestation unsupported on this device; using software registration" }
            return null
        }
        val request = try {
            val evidence = attestor.attest(clientKey.encoded)
            val signedHashPukClientSig = popSigner.signProofOfPossession()
            buildHardwareRegistrationRequest(clientName, clientKey.jwk, evidence, signedHashPukClientSig, redirectUris)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(e) { "Device attestation failed; falling back to software registration" }
            return null
        }
        Log.i { "Registering with ${attestor.attestationType} hardware attestation" }
        val result = attemptRegister(endpoint, ATTESTED_NO_RETRY_STATUSES, compat) { request }
        val status = (result.exceptionOrNull() as? ClientRegistrationException)?.response?.status
        if (status == HttpStatusCode.BadRequest) {
            Log.w { "Guard rejected the hardware attestation; discarding the attested key" }
            attestor.forget()
            state.removeAttestedKey()
        }
        return Registered(result, attestor.attestationType.toRegistered())
    }

    private fun HardwareAttestationType.toRegistered() = when (this) {
        HardwareAttestationType.APPLE -> RegisteredAttestationType.APPLE
        HardwareAttestationType.ANDROID -> RegisteredAttestationType.ANDROID
    }

    private fun wasRegisteredWith(registration: ClientRegistrationResponse?, currentKid: String): Boolean {
        val registeredKids = registration?.jwks?.keys.orEmpty().map { it.kid }.filter { it.isNotBlank() }
        if (registeredKids.isEmpty()) {
            Log.d { "Cached client registration has no recorded client key, re-registering" }
            return false
        }
        if (currentKid !in registeredKids) {
            Log.w { "Client instance key changed since registration, re-registering" }
            return false
        }
        return true
    }

    private suspend fun attemptRegister(
        endpoint: String,
        noRetryStatuses: Set<Int>,
        compat: LegacyApiCompat?,
        buildRequest: suspend () -> ClientRegistrationRequest,
    ): Result<ClientRegistrationResponse> {
        var lastException: ClientRegistrationException? = null
        repeat(maxRetries) { attempt ->
            try {
                val request = buildRequest()
                val body = compat?.registrationBody(request) ?: request.toJson()
                return Result.success(regApi.register(endpoint, request, body))
            } catch (ex: ClientRegistrationException) {
                lastException = ex
                val status = ex.response?.status?.value
                Log.w { "Attempt $attempt registration failed with $status: ${ex.message}" }
                if (status in noRetryStatuses) return Result.failure(ex)
                if (attempt == maxRetries - 1) {
                    Log.e { "Registration failed after $attempt attempts with $status: ${ex.message}" }
                    return Result.failure(ex)
                }
            }
            delay(1000L * (attempt + 1))
        }
        return Result.failure(lastException ?: Exception("Unexpected exception during registration"))
    }
}
