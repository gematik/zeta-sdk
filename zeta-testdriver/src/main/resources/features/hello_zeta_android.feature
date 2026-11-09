Feature: Hello ZETA Android

  Scenario: Hello ZETA with sekIDP login and OTP
    Given an Android device with the ZETA app
    When I tap "Hello Zeta"
    And I tap "Login"
    And I enter the test email if the app asks for it
    And I enter the OTP from the verification email
    And I tap "Confirm"
    Then the result shows "Hello ZETA!"
