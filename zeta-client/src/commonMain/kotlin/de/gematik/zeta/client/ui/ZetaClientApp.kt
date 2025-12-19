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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.ui.environment.toggle.EnvToggleComponent
import de.gematik.zeta.client.ui.hello.HelloZetaComponent
import de.gematik.zeta.client.ui.prescription.list.PrescriptionListComponent

@OptIn(ExperimentalReactiveStateApi::class)
@Composable
public fun ZetaClientApp() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box {
                EnvToggleComponent()
            }
            Box(
                modifier = Modifier.weight(1f),
            ) {
                PrescriptionListComponent()
            }
            Box {
                HelloZetaComponent()
            }
        }
//        Box(
//            modifier = Modifier
//                .fillMaxHeight()
//                .weight(1f)
//        ) {
//            HelloZetaComponent()
//        }
    }
}
