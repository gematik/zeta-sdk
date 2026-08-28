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

package de.gematik.zeta.client.ui.otp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val RESEND_COOLDOWN_SECONDS = 10

@Composable
public fun OtpEntryScreen(
    emailHint: String?,
    rejected: Boolean,
    onSubmit: (code: String) -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    var otpInput by remember { mutableStateOf("") }
    var resendCount by remember { mutableStateOf(0) }
    var secondsRemaining by remember { mutableStateOf(RESEND_COOLDOWN_SECONDS) }

    LaunchedEffect(rejected, resendCount) {
        secondsRemaining = RESEND_COOLDOWN_SECONDS
        while (secondsRemaining > 0) {
            delay(1000.milliseconds)
            secondsRemaining -= 1
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (emailHint != null) {
                    "Introduce the OTP sent to $emailHint"
                } else {
                    "Introduce the OTP"
                },
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text("Cancel")
            }
        }
        if (rejected) {
            Text(text = "Invalid code. Please try again.")
        }
        OutlinedTextField(
            value = otpInput,
            onValueChange = { otpInput = it.filter(Char::isDigit).take(6) },
            label = { Text("OTP Code") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(otpInput) },
            enabled = otpInput.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Confirm")
        }
        Button(
            onClick = {
                otpInput = ""
                resendCount++
                onResend()
            },
            enabled = secondsRemaining == 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (secondsRemaining > 0) "Resend email (${secondsRemaining}s)" else "Resend email",
            )
        }
    }
}
