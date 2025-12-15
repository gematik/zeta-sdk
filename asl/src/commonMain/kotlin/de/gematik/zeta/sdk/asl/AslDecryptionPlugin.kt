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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.InnerHeadersKey
import de.gematik.zeta.sdk.network.http.client.InnerStatusKey
import de.gematik.zeta.sdk.network.http.client.isAslEncryptedResponse
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray

/** ASL decryption plugin */
public fun aslDecryptionPlugin(aslApi: AslApi): ClientPlugin<Unit> =
    createClientPlugin("AslDecryptionPlugin") {
        val decryptPhase = PipelinePhase("AslDecrypt")
        client.responsePipeline.insertPhaseBefore(HttpResponsePipeline.Transform, decryptPhase)
        client.responsePipeline.intercept(decryptPhase) {
            val (typeInfo, subject) = it
            val response = context.response

            if (!shouldDecryptResponse(response)) {
                return@intercept
            }

            try {
                val encryptedBytes = when (subject) {
                    is ByteArray -> subject

                    is ByteReadChannel -> subject.readRemaining().readByteArray()

                    is Buffer -> subject.readByteArray()

                    is Source -> subject.readByteArray()

                    else -> {
                        Log.w { "Unexpected body type for ASL response: ${subject::class}" }
                        return@intercept
                    }
                }

                val innerPlain = aslApi.decrypt(encryptedBytes)
                val innerResponse = InnerHttpCodec().decodeResponse(innerPlain)

                context.attributes.put(InnerStatusKey, innerResponse.status)
                context.attributes.put(InnerHeadersKey, innerResponse.headers)

                proceedWith(HttpResponseContainer(typeInfo, innerResponse.body))
            } catch (e: Exception) {
                Log.e(e) { "ASL decryption failed" }
                throw e
            }
        }
    }

private fun shouldDecryptResponse(response: HttpResponse): Boolean {
    val path = response.request.url.encodedPath
    val contentType = response.headers[HttpHeaders.ContentType]
    return isAslEncryptedResponse(path, contentType)
}
