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

package de.gematik.zeta.client

import android.app.Application
import android.content.pm.ApplicationInfo
import de.gematik.zeta.client.config.AndroidConfig
import de.gematik.zeta.client.notification.ZetaNotifications
import de.gematik.zeta.logging.Log
import de.gematik.zeta.logging.ZetaLogger
import de.gematik.zeta.sdk.ZetaInit

public class ZetaClientApp : Application() {
    private companion object {
        private const val DEFAULT_TAG = "Zeta-SDK"
        private fun tag(tag: String?): String = "[${tag ?: DEFAULT_TAG}]"
    }
    override fun onCreate() {
        super.onCreate()

        Log.setLogger(object : ZetaLogger {
            override fun d(tag: String?, message: () -> String, throwable: Throwable?) {
                android.util.Log.d(tag(tag), message(), throwable)
            }
            override fun i(tag: String?, message: () -> String, throwable: Throwable?) {
                android.util.Log.i(tag(tag), message(), throwable)
            }
            override fun w(tag: String?, message: () -> String, throwable: Throwable?) {
                android.util.Log.w(tag(tag), message(), throwable)
            }
            override fun e(tag: String?, message: () -> String, throwable: Throwable?) {
                android.util.Log.e(tag(tag), message(), throwable)
            }
        })
        // Demo app: verbose logging (incl. the FCM pushkey needed to register a
        // device for test pushes) is enabled only on debuggable builds. A shipped
        // release build keeps logLevel at ERROR, so the pushkey and push message
        // content never reach logcat.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.initDebugLogger()
        }
        AndroidConfig.init(this)
        ZetaInit.initAndroid(this)
        // No-op unless the optional push-notification feature is enabled at build time.
        ZetaNotifications.integration?.onApplicationCreate(this)
    }
}
