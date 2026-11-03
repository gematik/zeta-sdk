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

package de.gematik.zeta.driver

import io.appium.java_client.AppiumDriver
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.options.UiAutomator2Options
import io.appium.java_client.ios.IOSDriver
import io.appium.java_client.ios.options.XCUITestOptions
import java.net.URI

public enum class DevicePlatform { ANDROID, IOS }

public object DeviceSessions {
    private val hub by lazy {
        URI.create(System.getenv("BS_HUB_URL") ?: "https://hub-cloud.browserstack.com/wd/hub").toURL()
    }

    private fun env(name: String): String =
        System.getenv(name) ?: error("Missing environment variable $name")

    public fun open(platform: DevicePlatform): AppiumDriver {
        val bstack = mapOf(
            "userName" to env("BS_USER"),
            "accessKey" to env("BS_KEY"),
        )

        return when (platform) {
            DevicePlatform.ANDROID -> AndroidDriver(
                hub,
                UiAutomator2Options().apply {
                    setApp(env("BS_ANDROID_APP_URL"))
                    setCapability("bstack:options", bstack + mapOf("deviceName" to "Google Pixel 8", "osVersion" to "14.0"))
                },
            )
            DevicePlatform.IOS -> IOSDriver(
                hub,
                XCUITestOptions().apply {
                    setApp(env("BS_IOS_APP_URL"))
                    setAutoAcceptAlerts(true)
                    setCapability("bstack:options", bstack + mapOf("deviceName" to "iPhone 16", "osVersion" to "18.5", "resignApp" to false))
                },
            )
        }
    }

    public fun quit(driver: AppiumDriver, passed: Boolean) {
        runCatching {
            val status = if (passed) "passed" else "failed"
            driver.executeScript("""browserstack_executor: {"action":"setSessionStatus","arguments":{"status":"$status"}}""")
        }
        driver.quit()
    }
}
