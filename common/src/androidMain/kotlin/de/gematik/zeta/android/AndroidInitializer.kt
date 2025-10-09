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

package de.gematik.zeta.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.startup.Initializer

/**
 * application context
 * It initialized using the [Initializer] from the androidx startup library
 */
@SuppressLint("StaticFieldLeak")
public lateinit var baseContext: Context

internal class AndroidInitializer : Initializer<AndroidInitializer> {
    override fun create(context: Context): AndroidInitializer {
        baseContext = context.applicationContext
        (baseContext as? Application)?.registerActivityLifecycleCallbacks(ActivityLifecycleObserver())
        return this
    }

    override fun dependencies(): List<Class<out Initializer<*>?>?> = emptyList()
}
