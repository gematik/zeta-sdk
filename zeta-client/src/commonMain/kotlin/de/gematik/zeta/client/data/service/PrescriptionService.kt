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

package de.gematik.zeta.client.data.service

import de.gematik.zeta.client.data.service.http.HttpClientProvider
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.di.DIContainer.POPP_TOKEN
import de.gematik.zeta.client.di.POPP_TOKEN_HEADER_NAME
import de.gematik.zeta.client.model.PrescriptionModel
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

public interface PrescriptionService {
    public suspend fun prescriptionList(): List<PrescriptionModel>
    public suspend fun prescription(id: Long): PrescriptionModel
    public suspend fun addPrescription(model: PrescriptionModel)
    public suspend fun putPrescription(id: Long, model: PrescriptionModel)
    public suspend fun deletePrescription(id: Long) }

public class PrescriptionServiceImpl(
    private val httpClientProvider: HttpClientProvider = DIContainer.httpClientProvider,
) : PrescriptionService {

    private val httpClient: ZetaHttpClient get() = httpClientProvider.provideHttpClient()

    override suspend fun prescriptionList(): List<PrescriptionModel> {
        return httpClient
            .get("api/erezept") {
                POPP_TOKEN?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
            }
            .validateResponseCode()
            .body()
    }

    override suspend fun prescription(id: Long): PrescriptionModel {
        return httpClient
            .get("api/erezept/$id") {
                POPP_TOKEN?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
            }
            .validateResponseCode()
            .body<PrescriptionModel>()
    }

    override suspend fun addPrescription(model: PrescriptionModel) {
        return httpClient
            .post("api/erezept") {
                POPP_TOKEN?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
                contentType(ContentType.Application.Json)
                setBody(model)
            }
            .validateResponseCode()
            .body()
    }

    override suspend fun putPrescription(id: Long, model: PrescriptionModel) {
        return httpClient
            .put("api/erezept/$id") {
                POPP_TOKEN?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
                contentType(ContentType.Application.Json)
                setBody(model)
            }
            .validateResponseCode()
            .body()
    }

    override suspend fun deletePrescription(id: Long) {
        return httpClient
            .delete("api/erezept/$id") {
                POPP_TOKEN?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
            }
            .validateResponseCode()
            .body()
    }
}

public suspend fun ZetaHttpResponse.validateResponseCode(): ZetaHttpResponse {
    if (status.value in 400..599) {
        val body = bodyAsText()
        throw HttpException("HTTP error: code = $status, body = $body")
    }
    return this
}

public class HttpException(message: String) : Exception(message)
