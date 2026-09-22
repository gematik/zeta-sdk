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

package de.gematik.zeta.sdk.asl.vau

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.asl.AslCertDataApi
import de.gematik.zeta.sdk.asl.AslStorage
import de.gematik.zeta.sdk.asl.AslTiRootStore
import de.gematik.zeta.sdk.asl.CertData
import de.gematik.zeta.sdk.asl.EncapsulationResult
import de.gematik.zeta.sdk.asl.HttpCertDataFetcher
import de.gematik.zeta.sdk.asl.K2Keys
import de.gematik.zeta.sdk.asl.M3InnerLayer
import de.gematik.zeta.sdk.asl.Message1Bundle
import de.gematik.zeta.sdk.asl.Message1Result
import de.gematik.zeta.sdk.asl.Message2
import de.gematik.zeta.sdk.asl.Message3Result
import de.gematik.zeta.sdk.asl.SignedVauPublicKeys
import de.gematik.zeta.sdk.asl.VauKeys
import de.gematik.zeta.sdk.asl.VauPairKeys
import de.gematik.zeta.sdk.asl.cbor
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.EcdhSigner
import de.gematik.zeta.sdk.crypto.Hkdf
import de.gematik.zeta.sdk.crypto.Kem
import de.gematik.zeta.sdk.crypto.RevocationHandlerImpl
import de.gematik.zeta.sdk.crypto.X509CertValidator
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.config.tls.sanMatchesHost
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.ExperimentalSerializationApi

@Suppress("UnsafeCallOnNullableType")
@OptIn(ExperimentalSerializationApi::class)
internal suspend fun processMessage2AndDeriveMessage3(
    message1: Message1Bundle,
    resultMessage1: Message1Result,
    kem: KemBundle,
    http: HttpContext,
    asl: AslContext,
    clock: ZetaClock,
): Message3Result {
    val message2 = parseMessage2(resultMessage1.response)
    val ssE = deriveSharedSecret(kem.mlKem, kem.ecdhKem, message1.keys, message2)
    val (k1_c2s, k1_s2c) = deriveSharedKeys(ssE)
    val signed = decryptSignedKeys(k1_s2c, message2.aeadCiphertext!!)

    if (http.tlsValidation) {
        Log.i { "Starting Certificate / OCSP validation" }
        val tiEnvironment = if (asl.prodEnvironment) AslTiRootStore.TiEnvironment.PRODUCTION else AslTiRootStore.TiEnvironment.REFERENCE
        validateSignedVauPublicKeys(
            signed = signed,
            validation = CertValidationBundle(
                http = http,
                certDataFetcher = HttpCertDataFetcher(http.client, http.request),
                certDataCache = asl.storage,
                tiTrustAnchors = AslTiRootStore(http.client, tiEnvironment).getTrustAnchors(clock),
                revocationChecker = asl.revocationChecker,
            ),
            requiredRoleOid = asl.requiredRoleOid,
            clock = clock,
        )
    } else {
        Log.i { "Certificate / OCSP validation disabled" }
    }

    val encaps = encapsulateForServer(kem.mlKem, kem.ecdhKem, signed)
    val inner = buildM3InnerLayer(encaps)

    val innerCipherText = encryptInnerLayer(k1_c2s, inner)

    val transcriptHash = computeTranscriptHash(
        m1 = message1.encoded,
        m2 = resultMessage1.response,
        m3 = innerCipherText,
    )
    val k2 = deriveK2(ssE, encaps.serverSharedSecret)

    return Message3Result(innerCipherText, k2, transcriptHash)
}

@OptIn(ExperimentalSerializationApi::class)
public fun parseMessage2(raw: ByteArray): Message2 {
    val m2 = cbor.decodeFromByteArray(Message2.serializer(), raw)
    require(m2.type == "M2") { "Wrong MessageType" }

    return m2
}

public fun EcPointP256.toUncompressedPoint(): ByteArray {
    require(crv == "P-256")
    require(x.size == 32 && y.size == 32)

    return byteArrayOf(0x04) + x + y
}

public fun EcPointP256.Companion.fromKemBytes(bytes: ByteArray): EcPointP256 {
    require(bytes.size == 65)
    val x = bytes.copyOfRange(1, 33)
    val y = bytes.copyOfRange(33, 65)
    return EcPointP256(x = x, y = y, crv = "P-256")
}

public fun deriveSharedSecret(ml768Kem: Kem, ecdhKem: Kem, keys: VauPairKeys, message2: Message2): ByteArray {
    val sharedSecreteEphemeralEc = ecdhKem.decapsulate(keys.ecdhKey.privateKey, message2.ecdhCiphertext.toUncompressedPoint())
    val sharedSecreteEphemeralMl = ml768Kem.decapsulate(keys.ml768Key.privateKey, message2.ml768Ciphertext)

    return sharedSecreteEphemeralEc + sharedSecreteEphemeralMl
}

public fun deriveSharedKeys(ephemeralSharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
    val k1All = Hkdf.hkdfSha256(
        inputKeyMaterial = ephemeralSharedSecret,
        salt = ByteArray(32) { 0 },
        info = ByteArray(0),
        outLen = 64,
    )
    val k1Client2Server = k1All.copyOfRange(0, 32)
    val k1Server2Client = k1All.copyOfRange(32, 64)

    return k1Client2Server to k1Server2Client
}

@OptIn(ExperimentalSerializationApi::class)
public fun decryptSignedKeys(k1S2c: ByteArray, signedKeysCt: ByteArray): SignedVauPublicKeys {
    val plain = AesGcmCipherImpl().decrypt(k1S2c, signedKeysCt)
    return cbor.decodeFromByteArray(SignedVauPublicKeys.serializer(), plain)
}

@OptIn(ExperimentalSerializationApi::class)
public fun encapsulateForServer(mlKem: Kem, ecdhKem: Kem, signed: SignedVauPublicKeys): EncapsulationResult {
    val vauKeys = cbor.decodeFromByteArray(VauKeys.serializer(), signed.signedPublicKeys)
    val serverEcdhPubBytes = vauKeys.ecdhPublicKey
    val ecdhKemResult = ecdhKem.encapsulate(serverEcdhPubBytes.toUncompressedPoint())
    val mlKemResult = mlKem.encapsulate(vauKeys.mlKemPublicKey)

    return EncapsulationResult(
        serverSharedSecret = ecdhKemResult.sharedSecret + mlKemResult.sharedSecret,
        ecdhCiphertext = ecdhKemResult.ciphertext,
        mlKemCiphertext = mlKemResult.ciphertext,
    )
}

public fun deriveK2(ephemeralSharedSecret: ByteArray, serverSharedSecret: ByteArray): K2Keys {
    val ss = ephemeralSharedSecret + serverSharedSecret

    val okm = Hkdf.hkdfSha256(
        inputKeyMaterial = ss,
        salt = ByteArray(32) { 0 },
        info = ByteArray(0),
        outLen = 160,
    )

    return K2Keys(
        outputKeyingMaterial160 = okm,
        clientToServerConfirmationKey = okm.copyOfRange(0, 32),
        clientToServerAppDataKey = okm.copyOfRange(32, 64),
        serverToClientConfirmationKey = okm.copyOfRange(64, 96),
        serverToClientAppDataKey = okm.copyOfRange(96, 128),
        keyId = okm.copyOfRange(128, 160),
    )
}

public fun buildM3InnerLayer(encaps: EncapsulationResult): M3InnerLayer =
    M3InnerLayer(
        ecdhCiphertext = EcPointP256.fromKemBytes(encaps.ecdhCiphertext),
        mlKemCiphertext = encaps.mlKemCiphertext,
        erpEnabled = false,
        esoEnabled = false,
    )

@OptIn(ExperimentalSerializationApi::class)
public fun encryptInnerLayer(k1Client2Server: ByteArray, inner: M3InnerLayer): ByteArray {
    val innerCbor = cbor.encodeToByteArray(M3InnerLayer.serializer(), inner)
    return AesGcmCipherImpl().encrypt(k1Client2Server, innerCbor)
}

@OptIn(ExperimentalSerializationApi::class)
public fun computeTranscriptHash(m1: ByteArray, m2: ByteArray, m3: ByteArray): ByteArray {
    val transcript = m1 + m2 + m3
    return hashWithSha256(transcript)
}

public fun encryptKeyConfirmation(clientToServerConfirmationKey: ByteArray, transcriptHash: ByteArray): ByteArray =
    AesGcmCipherImpl().encrypt(clientToServerConfirmationKey, transcriptHash)

@OptIn(ExperimentalSerializationApi::class)
internal suspend fun validateSignedVauPublicKeys(
    signed: SignedVauPublicKeys,
    validation: CertValidationBundle,
    clock: ZetaClock,
    requiredRoleOid: String,
) {
    decodeAndValidateVauKeys(signed, clock)

    val certHashHex = signed.certificateHash.toHexString()
    val certVersion = signed.certificateDescriptionVersion

    val certData = validation.certDataCache.getCachedCertData(certHashHex, certVersion)
        ?: validation.certDataFetcher.fetch(certHashHex, certVersion).also { fetched ->
            validation.certDataCache.saveCachedCertData(certHashHex, certVersion, fetched)
        }

    val resourceHost = hostOf(validation.http.request.url.toString())

    validateCertificate(certData, resourceHost, validation)
    validateChain(certData, validation)
    validateRoleOid(certData.cert, requiredRoleOid, validation.certChainValidator)
    validateSignature(signed, certData.cert, validation)
    validation.revocationChecker.validate(
        stapledOcspResponse = signed.ocspResponse.takeIf { it.isNotEmpty() },
        certDer = certData.cert,
        issuerDer = certData.ca,
    )
}

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeAndValidateVauKeys(
    signed: SignedVauPublicKeys,
    clock: ZetaClock,
): VauKeys {
    val vauKeys = cbor.decodeFromByteArray(VauKeys.serializer(), signed.signedPublicKeys)
    validateVauKeyLifetime(vauKeys.expiresAt, vauKeys.issuedAt, clock.now().epochSeconds)
    require(vauKeys.ecdhPublicKey.crv == "P-256") {
        "ECDH key must use P-256, got ${vauKeys.ecdhPublicKey.crv}"
    }
    require(vauKeys.ecdhPublicKey.x.size == 32 && vauKeys.ecdhPublicKey.y.size == 32) {
        "ECDH coordinates must be 32 bytes each"
    }
    require(vauKeys.mlKemPublicKey.size == 1184) {
        "ML-KEM-768 key must be 1184 bytes, got ${vauKeys.mlKemPublicKey.size}"
    }
    return vauKeys
}

internal fun validateCertificate(
    certData: CertData,
    resourceHost: String,
    validation: CertValidationBundle,
) {
    validateSan(certData.cert, resourceHost, validation.certChainValidator)
    validation.certChainValidator.checkValidity(certData.cert)
}

internal fun validateChain(
    certData: CertData,
    validation: CertValidationBundle,
) {
    val chain = buildList {
        add(certData.cert)
        if (certData.ca.isNotEmpty()) add(certData.ca)
        addAll(certData.rcaChain.filter { it.isNotEmpty() })
    }
    if (validation.tiTrustAnchors.isEmpty()) {
        error("Chain validation aborted: no TI trust anchors loaded")
    }
    if (chain.size <= 1) {
        error("Chain validation aborted: incomplete chain (size=${chain.size})")
    }
    validation.certChainValidator.validateCertChain(chain, validation.tiTrustAnchors)
}

internal fun validateRoleOid(
    certDer: ByteArray,
    requiredRoleOid: String,
    certChainValidator: X509CertValidator,
) {
    val certRoleOids = certChainValidator.getProfessionOids(certDer)
    require(requiredRoleOid in certRoleOids) {
        "TI certificate missing required Role-OID: required=$requiredRoleOid, " +
            "present=${certRoleOids.joinToString()}"
    }
    Log.i { "Role-OID validated: $requiredRoleOid" }
}

internal fun validateSignature(
    signed: SignedVauPublicKeys,
    certDer: ByteArray,
    validation: CertValidationBundle,
) {
    val signingPubKey = validation.certChainValidator.getPublicKey(certDer)
    require(
        validation.ecdhSigner.verify(
            publicKey = signingPubKey,
            data = signed.signedPublicKeys,
            signature = signed.es256Signature,
        ),
    ) { "ES256 signature invalid for signed_pub_keys" }
}

public const val SECONDS_PER_DAY: Int = 60 * 60 * 24
private const val MAX_KEY_LIFETIME_DAYS = 30
public const val MAX_KEY_LIFETIME_SECONDS: Int = MAX_KEY_LIFETIME_DAYS * SECONDS_PER_DAY

internal fun validateVauKeyLifetime(
    expiresAt: Long,
    issuedAt: Long,
    nowEpochSeconds: Long,
) {
    require(expiresAt > nowEpochSeconds) {
        "ASL keys expired: expiresAt=$expiresAt, now=$nowEpochSeconds"
    }

    val actualLifetimeSeconds = expiresAt - issuedAt
    require(actualLifetimeSeconds <= MAX_KEY_LIFETIME_SECONDS) {
        "ASL keys lifetime exceeds $MAX_KEY_LIFETIME_DAYS days: " +
            "issuedAt=$issuedAt, expiresAt=$expiresAt, " +
            "lifetimeDays=${actualLifetimeSeconds / SECONDS_PER_DAY}"
    }
}

internal fun validateSan(
    certDer: ByteArray,
    host: String,
    certChainValidator: X509CertValidator,
) {
    val sanDnsNames = certChainValidator.getSanDnsNames(certDer)

    require(sanDnsNames.any { san -> sanMatchesHost(san, host) }) {
        "ASL certificate SAN does not match resource host: host=$host, SANs=$sanDnsNames"
    }
    Log.i { "ASL certificate SAN validated: host=$host matched in $sanDnsNames" }
}

internal data class KemBundle(val mlKem: Kem, val ecdhKem: Kem)

internal data class HttpContext(
    val client: ZetaHttpClient,
    val request: HttpRequestBuilder,
    val tlsValidation: Boolean = true,
)

internal data class AslContext(
    val prodEnvironment: Boolean,
    val requiredRoleOid: String,
    val storage: AslStorage,
    val revocationChecker: RevocationChecker,
)

internal data class CertValidationBundle(
    val http: HttpContext,
    val certDataFetcher: AslCertDataApi,
    val certDataCache: AslStorage,
    val certChainValidator: X509CertValidator = X509CertValidator(),
    val ocspValidator: RevocationHandlerImpl = RevocationHandlerImpl(),
    val ecdhSigner: EcdhSigner = EcdhSigner(),
    val tiTrustAnchors: List<ByteArray>,
    val revocationChecker: RevocationChecker,
)
