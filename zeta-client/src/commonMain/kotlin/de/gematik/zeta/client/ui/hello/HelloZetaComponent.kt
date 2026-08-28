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

package de.gematik.zeta.client.ui.hello

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.gematik.zeta.client.notification.notificationTestAction
import de.gematik.zeta.client.ui.otp.EmailEntryScreen

@Composable
private fun LoadingButton(
    label: String,
    action: HelloZetaViewModel.SdkAction,
    loadingAction: HelloZetaViewModel.SdkAction?,
    onClick: () -> Unit,
    onCancel: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val isThisLoading = loadingAction == action
    val isOtherLoading = loadingAction != null && !isThisLoading

    Button(
        onClick = if (isThisLoading && onCancel != null) onCancel else onClick,
        enabled = enabled && !isOtherLoading,
        colors = if (isThisLoading) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.buttonColors(),
    ) {
        if (isThisLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Text(label)
        }
    }
}

@Composable
public fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
public fun ResultCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val isError = text.contains("\"error\"")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
public fun HelloZetaComponent(viewModel: HelloZetaViewModel) {
    val lastResult by viewModel.lastResult.collectAsState()
    val loadingAction by viewModel.loadingAction.collectAsState()
    val pusherConfigured by viewModel.pusherConfigured.collectAsState()
    val pushTestAvailable = remember { notificationTestAction() != null }
    var showChangeEmail by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionLabel("Flow")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LoadingButton("Hello Zeta", HelloZetaViewModel.SdkAction.HELLO_ZETA, loadingAction, viewModel::helloZeta, onCancel = viewModel::cancelCurrentAction)
            LoadingButton("Discover", HelloZetaViewModel.SdkAction.DISCOVER, loadingAction, viewModel::doDiscovery)
            LoadingButton("Register", HelloZetaViewModel.SdkAction.REGISTER, loadingAction, viewModel::doRegistration)
            LoadingButton("Authenticate", HelloZetaViewModel.SdkAction.AUTHENTICATE, loadingAction, viewModel::doAuthentication)
        }

        SectionLabel("Session")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LoadingButton("Status", HelloZetaViewModel.SdkAction.STATUS, loadingAction, viewModel::statusSdk)
            LoadingButton("Logout", HelloZetaViewModel.SdkAction.LOGOUT, loadingAction, viewModel::logoutAuthorization)
            LoadingButton(
                "Clear Registration",
                HelloZetaViewModel.SdkAction.CLEAR_REGISTRATION,
                loadingAction,
                viewModel::forgetRegistration,
            )
            LoadingButton(
                "Forget",
                HelloZetaViewModel.SdkAction.FORGET,
                loadingAction,
                viewModel::forgetAuthorization,
            )
            LoadingButton(
                "Change Email",
                HelloZetaViewModel.SdkAction.CHANGE_EMAIL,
                loadingAction,
                { showChangeEmail = true },
            )
            if (pushTestAvailable) {
                LoadingButton("Test Push", HelloZetaViewModel.SdkAction.TEST_PUSHER, loadingAction, viewModel::registerTestPusher)
            }
        }

        if (pushTestAvailable) {
            SectionLabel("Channels")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LoadingButton(
                    "Subscribe All",
                    HelloZetaViewModel.SdkAction.SUBSCRIBE_ALL_CHANNELS,
                    loadingAction,
                    viewModel::subscribeAllChannels,
                    enabled = pusherConfigured,
                )
                LoadingButton(
                    "Unsubscribe All",
                    HelloZetaViewModel.SdkAction.UNSUBSCRIBE_ALL_CHANNELS,
                    loadingAction,
                    viewModel::unsubscribeAllChannels,
                    enabled = pusherConfigured,
                )
            }
        }

        if (lastResult.isNotBlank()) {
            ResultCard(lastResult)
        }
    }

    if (showChangeEmail) {
        Dialog(
            onDismissRequest = { showChangeEmail = false },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.6f),
                ) {
                    EmailEntryScreen(
                        onSubmit = { email ->
                            showChangeEmail = false
                            viewModel.changeEmail(email)
                        },
                        onCancel = { showChangeEmail = false },
                    )
                }
            }
        }
    }
}
