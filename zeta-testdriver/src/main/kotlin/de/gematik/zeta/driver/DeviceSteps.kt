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

import de.gematik.zeta.driver.core.oidc.MailCatcherOtpClient
import de.gematik.zeta.driver.model.toKtorLogLevel
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.appium.java_client.AppiumBy
import io.appium.java_client.AppiumDriver
import io.appium.java_client.HasSettings
import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

public class DeviceSteps {
    private lateinit var driver: AppiumDriver

    private val mail by lazy {
        MailCatcherOtpClient(
            System.getenv("MAILCATCHER_URL")
                ?: error("Missing environment variable MAILCATCHER_URL"),
        )
    }
    private fun mailClient() = ZetaHttpClientBuilder()
        .disableServerValidation(true)
        .logging(Log.logLevel.toKtorLogLevel())
        .build()

    private val isIos: Boolean
        get() = driver.capabilities.platformName?.toString().equals("ios", ignoreCase = true)

    private fun quoted(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun otpField(): By =
        if (isIos) {
            AppiumBy.iOSNsPredicateString(
                "name == 'OTP Code' OR label == 'OTP Code' OR " +
                    "type IN {'XCUIElementTypeTextField','XCUIElementTypeSecureTextField','XCUIElementTypeTextView'}",
            )
        } else {
            By.className("android.widget.EditText")
        }

    private fun byText(label: String): By {
        val v = quoted(label)
        return if (isIos) {
            AppiumBy.iOSNsPredicateString("label == $v OR name == $v OR value == $v")
        } else {
            AppiumBy.androidUIAutomator("new UiSelector().text($v)")
        }
    }

    private val wait: WebDriverWait get() = WebDriverWait(driver, Duration.ofSeconds(30))

    private fun byTextContains(fragment: String): By {
        val v = quoted(fragment)
        return if (isIos) {
            AppiumBy.iOSNsPredicateString("label CONTAINS $v OR value CONTAINS $v")
        } else {
            AppiumBy.androidUIAutomator("new UiSelector().textContains($v)")
        }
    }

    @Given("an Android device with the ZETA app")
    public fun openAndroid() { driver = DeviceSessions.open(DevicePlatform.ANDROID) }

    @Given("an iOS device with the ZETA app")
    public fun openIos() {
        driver = DeviceSessions.open(DevicePlatform.IOS)
        (driver as? HasSettings)?.setSetting(
            "acceptAlertButtonSelector",
            "**/XCUIElementTypeButton[`label IN {'Allow','OK','Continue','Erlauben','Fortfahren'}`]",
        )
    }

    @When("I tap {string}")
    public fun tap(label: String) {
        if (label == "Login") runBlocking { mail.markBaseline(mailClient()) }
        wait.until(ExpectedConditions.elementToBeClickable(byText(label))).click()
    }

    @Then("{string} shows {string}")
    public fun shows(id: String, expected: String) {
        val text = wait.until(
            ExpectedConditions.visibilityOfElementLocated(AppiumBy.accessibilityId(id)),
        ).text
        check(text.contains(expected)) { "Expected '$expected' but was '$text'" }
    }

    @After
    public fun close(scenario: Scenario) {
        if (::driver.isInitialized) DeviceSessions.quit(driver, passed = !scenario.isFailed)
    }

    @When("I enter the OTP from the verification email")
    public fun enterOtp() {
        val otp = runBlocking { mail.waitForOtp(mailClient(), timeout = 60.seconds) }
        val field = wait.until(ExpectedConditions.elementToBeClickable(otpField()))

        field.click()
        field.sendKeys(otp)
    }

    @Then("the result shows {string}")
    public fun resultShows(expected: String) {
        var scrolls = 0
        wait.until { d ->
            d.findElements(byTextContains("\"error\"")).firstOrNull()?.let {
                error("App returned an error: ${it.text}")
            }
            d.findElements(byTextContains(expected)).firstOrNull() ?: run {
                if (isIos && scrolls < 5) {
                    scrolls++
                    runCatching { driver.executeScript("mobile: scroll", mapOf("direction" to "down")) }
                }
                null
            }
        }
    }
}
