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

package de.gematik.zeta.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.ui.environment.toggle.EnvToggleComponent
import de.gematik.zeta.client.ui.hello.HelloZetaComponent
import de.gematik.zeta.client.ui.hello.HelloZetaViewModel
import de.gematik.zeta.client.ui.otp.EmailEntryScreen
import de.gematik.zeta.client.ui.otp.OtpEntryScreen
import de.gematik.zeta.client.ui.otp.OtpFlowPrompt
import de.gematik.zeta.client.ui.prescription.list.PrescriptionListComponent
import de.gematik.zeta.client.ui.settings.SettingsComponent
import de.gematik.zeta.client.ui.utils.buildViewModel
import de.gematik.zeta.sdk.notifications.model.Channel
import kotlinx.coroutines.launch

@OptIn(ExperimentalReactiveStateApi::class)
@Composable
public fun ZetaClientApp() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val otpState by DIContainer.otpCallback.state.collectAsState()
    val helloViewModel by buildViewModel {
        HelloZetaViewModel(scope)
    }
    val subscribedChannels by helloViewModel.subscribedChannels.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Settings", modifier = Modifier.padding(16.dp))
                SettingsComponent()
                EnvToggleComponent()
            }
        },
    ) {
        ZetaClientContent(
            subscribedChannels = subscribedChannels,
            helloViewModel = helloViewModel,
            onOpenDrawer = { drawerScope.launch { drawerState.open() } },
        )
    }

    otpState?.let { prompt ->
        OtpDialog(prompt)
    }
}

@Composable
private fun ZetaClientContent(
    subscribedChannels: List<Channel>,
    helloViewModel: HelloZetaViewModel,
    onOpenDrawer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box {
                IconButton(onClick = onOpenDrawer) {
                    Text("\u2630")
                }
            }
            Box(
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PrescriptionListComponent(
                        modifier = if (subscribedChannels.isEmpty()) {
                            Modifier.weight(1f).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        showList = subscribedChannels.isEmpty(),
                    )
                    if (subscribedChannels.isNotEmpty()) {
                        SubscribedChannelsList(
                            channels = subscribedChannels,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
            Box {
                HelloZetaComponent(helloViewModel)
            }
        }
    }
}

@Composable
private fun OtpDialog(prompt: OtpFlowPrompt) {
    val cancel: () -> Unit = {
        DIContainer.otpCallback.onCancelled()
    }

    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.6f),
            ) {
                when (prompt) {
                    is OtpFlowPrompt.CollectEmail -> EmailEntryScreen(
                        onSubmit = { email -> DIContainer.otpCallback.onEmailEntered(email) },
                        onCancel = cancel,
                    )
                    is OtpFlowPrompt.EnterOtp -> OtpEntryScreen(
                        emailHint = prompt.emailHint,
                        rejected = prompt.rejected,
                        onSubmit = { code -> DIContainer.otpCallback.onOtpEntered(code) },
                        onResend = { DIContainer.otpCallback.onResendRequested() },
                        onCancel = cancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscribedChannelsList(
    channels: List<Channel>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = channel.id, style = MaterialTheme.typography.bodyLarge)
                    Text(text = channel.status.name.lowercase(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
