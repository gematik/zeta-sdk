# Notifications Component

> **Preview:** push notifications are part of the ZETA 2.0 specification and still in preview — APIs and flows may change.

Client for the ZETA Notification Service (NS) that is co-deployed with the guard.
It manages pushers (push registrations of a device) and notification channels, and reads the notification history.
Receiving and displaying the actual push messages (FCM/APNs) is the job of the embedding app, not of this module.

## Design

- **NotificationClient**: Public interface the app uses. Operations: `getPushers`, `registerPusher`, `updatePusher`, `deletePusher`, `getAvailableChannels`, `getLocalChannels`, `setLocalChannels`, `decryptPushNotification`, `getNotification`, `getNotifications`.
- **NotificationApiClient / NotificationApiClientImpl**: HTTP contract to the NS device API (`GET /pushers`, `POST /pushers/set`, `GET /channels`, `GET`/`POST /channels/{pushkey}`, `GET /history/{notification_id}`, `GET /history/device/{pushkey}`). Adds per request:
  * a service-specific access token (`Authorization: dpop <token>`) with least-privilege scope per operation (`NotificationScopes`),
  * a fresh DPoP proof (`NotificationDpopProvider`).
  * On 401: invalidates the token and retries once. On 429: bounded backoff retry (`RateLimitRetryPolicy`). Errors map to `NotificationApiException` subtypes (400/401/403/429/other; on the history endpoints also 404/501 → `NotificationHistoryNotFoundException`).
- **NotificationTokenProvider / ReauthNotificationTokenProvider**: Obtains the NS-specific access token (own `resource`/scopes, separate from the resource-server token).
- **LocalPusherStorage**: Remembers the pusher registered from this device, so channel and history operations don't need an explicit `pushkey`.
- **Models**: `Pusher`, `PusherConfig`, `PusherEncryption`, `Channel`, `HistoricNotification`, `HistoricNotifications`.
- **NotificationConfig**: Enables and configures the integration (see Usage).

NS endpoints and their scopes (A_29979, normative scope table):

| Endpoint              | Method | Scope                                                            |
|-----------------------|--------|------------------------------------------------------------------|
| `/pushers`            | GET    | `notification.pusher.read`                                       |
| `/pushers/set`        | POST   | `notification.pusher.write`                                      |
| `/channels`           | GET    | `notification.channel.read`                                      |
| `/channels/{pushkey}` | GET    | `notification.channel.read`                                      |
| `/channels/{pushkey}` | POST   | `notification.channel.write`                                     |
| `/history/*`          | GET    | `notification.history.read` (optional NS feature, A_29974)       |

## How it works ?

```mermaid
sequenceDiagram
participant App
participant NC as NotificationClient
participant API as NotificationApiClientImpl
participant NS as Notification Service

App->>NC: registerPusher(PusherConfig)
NC->>API: setPusher(kind=null) - deregister previous local pusher (if any)
API->>NS: POST /pushers/set
NC->>NC: create PusherEncryption (32-byte ISS, aes-hmac-sha256)
NC->>API: setPusher(pusher + encryption)
API->>NS: POST /pushers/set (token + DPoP)
NC->>NC: save local pusher (pushkey, appId)

App->>NC: setLocalChannels(channels)
NC->>API: setChannel(localPushkey, channels)
API->>NS: POST /channels/{pushkey}
```

1. The app registers the platform push token (e.g. FCM token) as `pushkey` via `registerPusher`. If a different pusher was registered from this device before, it is deregistered first (`kind = null`).
2. The SDK generates the ISS encryption key material and attaches it to the registration.
3. Channel operations (`getLocalChannels`, `setLocalChannels`) and `getNotifications` use the locally stored pushkey automatically.
4. Every NS call fetches a valid NS access token and signs a fresh DPoP proof. Discovery of the NS (well-known metadata) happens lazily on the first operation, not when the client is obtained.

## Usage

Notifications are opt-in. Pass a `NotificationConfig` in `BuildConfig`; `null` (the default) disables them entirely:

```kotlin
val sdk = ZetaSdk.build(
    resource,
    BuildConfig(
        // ...
        notificationConfig = NotificationConfig(), // defaults: wellKnownSubpath = "notification-service", apiBasePath = "/push/v1"
    ),
)

val notifications = sdk.notifications() // Android/iOS only
notifications.registerPusher(
    PusherConfig(pushkey = fcmToken, appId = "de.example.app"),
)
```

`NotificationConfig` fields:

| Field                | Default                  | Description                                                                 |
|----------------------|--------------------------|-----------------------------------------------------------------------------|
| wellKnownSubpath     | `notification-service`   | Subpath of the NS well-known metadata on the resource host, resulting in `https://{resource-host}/.well-known/oauth-protected-resource/{wellKnownSubpath}` |
| apiBasePath          | `/push/v1`               | API prefix of the NS at the PEP                                             |
| rateLimitRetryPolicy | `RateLimitRetryPolicy()` | Retry behaviour for 429 responses                                           |

Platform availability:
- `ZetaSdkClient.notifications()` exists on **Android and iOS** only (push transport is a mobile feature).
- JVM has an internal `notificationsForTesting()` used by the test driver.

### Notification history

If the NS operator has enabled the history (A_29974), the app can read the notifications sent to this device after delivery:

```kotlin
val history = notifications.getNotifications().notifications   // this device, via the stored pushkey
val one = notifications.getNotification(history.first().identifier)
```

- `identifier` is the Notification ID generated by the NS. It is **not** the `identifier` of a received push, which carries the resource server's `reference`, so a push cannot be looked up in the history directly.
- `payload` is returned as the `JsonObject` the NS sent; its properties are defined by the Fachdienst.
- The history is not cached or stored locally; every call goes to the NS.
- A malformed list item is skipped (only the count is logged); the rest of the list is returned.
- `404`/`501` throw `NotificationHistoryNotFoundException` with the NS `errorCode` (e.g. `NOTIFICATION_NOT_FOUND`, `PUSHER_NOT_FOUND`). The NS gives the same answer for an unknown ID, another user's data and a deactivated history, so a deactivated history surfaces either as this exception or as an empty list.
- The `notification.history.read` scope is only requested when the NS advertises it in `scopes_supported`. Without it, a PEP enforcing the scope rejects the history calls with `403` (`NotificationApiForbiddenException`).

Push transport (receiving messages) is not part of this module. The demo app in `zeta-client` shows an FCM integration on Android (`ZetaFirebaseMessagingService`) and an APNs integration on iOS (`AppDelegate.swift` with `IosPushBridge`); on iOS it decrypts pushes only while the app is in the foreground.
