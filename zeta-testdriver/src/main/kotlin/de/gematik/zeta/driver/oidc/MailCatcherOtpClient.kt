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

package de.gematik.zeta.driver.oidc

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class MailCatcherMessage(
    val id: Int,
    val subject: String,
)

public class MailCatcherOtpClient(
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun waitForOtp(
        client: ZetaHttpClient,
        subjectContains: String = DEFAULT_SUBJECT_FILTER,
        codeLength: Int = DEFAULT_CODE_LENGTH,
        timeout: Duration = DEFAULT_TIMEOUT,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ): String {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val messages = fetchMessages(client)
            val match = messages.lastOrNull { it.subject.contains(subjectContains, ignoreCase = true) }
            if (match != null) {
                extractCode(client, match.id, codeLength)?.let { return it }
            }
            delay(pollInterval)
        }
        error("Timed out after $timeout waiting for OTP email (subject contains \"$subjectContains\")")
    }

    private suspend fun fetchMessages(client: ZetaHttpClient): List<MailCatcherMessage> {
        val listJson = client.get("$baseUrl$MESSAGES_PATH").bodyAsText()
        return json.decodeFromString(listJson)
    }

    private suspend fun extractCode(client: ZetaHttpClient, messageId: Int, codeLength: Int): String? {
        val plain = client.get("$baseUrl$MESSAGES_PATH/$messageId$PLAIN_BODY_SUFFIX").bodyAsText()
        return Regex("""\b\d{$codeLength}\b""").find(plain)?.value
    }

    private companion object {
        const val MESSAGES_PATH = "/messages"
        const val PLAIN_BODY_SUFFIX = ".plain"
        const val DEFAULT_SUBJECT_FILTER = "verification code"
        const val DEFAULT_CODE_LENGTH = 6
        val DEFAULT_TIMEOUT = 30.seconds
        val DEFAULT_POLL_INTERVAL = 1.seconds
    }
}
