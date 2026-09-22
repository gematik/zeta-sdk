 # OIDC Flow

curl http://localhost:8080/testdriver-api/reset

# Request (Access Token + TOFU + Resource)
curl http://localhost:8080/proxy/hellozeta &

# Request (Internal method to know if email needs to be collected or OTP has to be sent)
curl http://localhost:8080/oidc/status

# Send OTP
curl -X POST http://localhost:8080/oidc/verify-otp \
-H "Content-Type: application/json" \
-d '{"code": "324434"}'

# Send OTP (when no email received)
curl -X POST http://localhost:8080/oidc/resend-otp

# Collect email
curl -X POST http://localhost:8080/oidc/collect-email \
-H "Content-Type: application/json" \
-d '{"email": "zeta@mailgun.com"}'

# Notifications (push pusher/channel management)

# Register a pusher (device_display_name and data are required by the Notification Service;
# omitting them fails with 500 "Missing parameters: device_display_name, data")
curl -X POST http://localhost:8080/testdriver-api/notifications/pushers \
-H "Content-Type: application/json" \
-d '{"pushkey": "abcdef", "appId": "de.gematik.app", "appDisplayName": "Demo", "deviceDisplayName": "Demo device", "lang": "de", "data": {"url": "https://push-gateway.example/_matrix/push/v1/notify", "format": "event_id_only"}}'

# List pushers
curl http://localhost:8080/testdriver-api/notifications/pushers

# List available channels
curl http://localhost:8080/testdriver-api/notifications/channels

# Set channels for the local pusher
curl -X POST http://localhost:8080/testdriver-api/notifications/channels/local \
-H "Content-Type: application/json" \
-d '{"channels": [{"id": "news", "status": "enabled"}]}'

# Delete a pusher
curl -X DELETE "http://localhost:8080/testdriver-api/notifications/pushers?pushkey=abcdef&appId=de.gematik.app"

# Set the email and KVNR via API
curl -X POST http://localhost:8080/oidc/kvnr-email \
-H "Content-Type: application/json" \
-d '{"kvnr": "X110411675", "email": "test@l21s.de"}'
