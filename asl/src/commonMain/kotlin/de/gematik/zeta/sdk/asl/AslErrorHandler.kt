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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.ExperimentalSerializationApi

public suspend fun handleMessageResponse(response: ZetaHttpResponse): ZetaHttpResponse {
    return when (response.status) {
        HttpStatusCode.OK -> response

        else -> {
            val errorMessage = decodeAslError(response.raw)
            Log.e { "ASL response error [${errorMessage.errorCode}] ${errorMessage.errorMessage}" }
            throw AslException(response, errorMessage.errorMessage, errorMessage.errorCode)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
public suspend fun decodeAslError(response: HttpResponse): AslErrorMessage =
    cbor.decodeFromByteArray(AslErrorMessage.serializer(), response.bodyAsBytes())

public class AslException(public val response: ZetaHttpResponse, errorMessage: String, errorCode: String) : Exception(errorMessage)
