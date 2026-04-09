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
package de.gematik.zeta;

import de.gematik.zeta.logging.Log;
import de.gematik.zeta.sdk.*;
import de.gematik.zeta.sdk.attestation.model.AttestationConfig;
import de.gematik.zeta.sdk.attestation.model.PlatformProductId;
import de.gematik.zeta.sdk.authentication.AuthConfig;
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider;
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider;
import de.gematik.zeta.sdk.authentication.smcb.ConnectorApiImpl;
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider;
import de.gematik.zeta.sdk.network.http.client.HttpClientExtension;
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient;
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder;
import io.ktor.client.plugins.logging.LogLevel;
import kotlin.Unit;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import static de.gematik.zeta.sdk.WsClientExtensionKt.stompConnectFrame;
import static de.gematik.zeta.sdk.WsClientExtensionKt.stompSendFrame;
import static de.gematik.zeta.sdk.WsClientExtensionKt.stompSubscribeFrame;

/**
 * Demonstration entry point for exercising the SDK against a ZETA environment.
 *
 * <p>The class keeps the sample intentionally simple, but the WebSocket CRUD flow now waits for
 * server replies and correlates them per session so parallel runs stay deterministic.
 */
public class Main {
    public static final String SMB_KEYSTORE_FILE = "SMB_KEYSTORE_FILE";
    public static final String SMB_KEYSTORE_ALIAS = "SMB_KEYSTORE_ALIAS";
    public static final String SMB_KEYSTORE_PASSWORD = "SMB_KEYSTORE_PASSWORD";
    public static final String ENVIRONMENTS = "ENVIRONMENTS";
    public static final String FACHDIENST_URL = "FACHDIENST_URL";
    public static final String SMCB_BASE_URL = "SMCB_BASE_URL";
    public static final String SMCB_MANDANT_ID = "SMCB_MANDANT_ID";
    public static final String SMCB_CLIENT_SYSTEM_ID = "SMCB_CLIENT_SYSTEM_ID";
    public static final String SMCB_WORKSPACE_ID = "SMCB_WORKSPACE_ID";
    public static final String SMCB_USER_ID = "SMCB_USER_ID";
    public static final String SMCB_CARD_HANDLE = "SMCB_CARD_HANDLE";
    public static final String DISABLE_SERVER_VALIDATION = "DISABLE_SERVER_VALIDATION";
    public static final String ASL_PROD = "ASL_PROD";
    public static final String POPP_TOKEN = "POPP_TOKEN";
    public static final String POPP_TOKEN_HEADER_NAME = "PoPP";
    public static final String WS_SERVER_CONTEXT_PATH = "WS_SERVER_CONTEXT_PATH";

    public static final String WEBSOCKETS_TAG = "Websockets";
    public static final String MAIN_TAG = "Main";

    /**
     * Runs the demo client with the optional properties file provided in {@code args[0]}.
     *
     * @param args optional CLI arguments, where the first argument points to a properties file
     */
    // This java client just demonstrates how to use the kotlin ZETA API, and not suitable for production
    @SuppressWarnings("java:S4507")
    public static void main(String[] args) {
        Log.INSTANCE.initDebugLogger();
        Log.INSTANCE.i(null, MAIN_TAG,() -> "Hello and welcome!");
        int exitCode = 0;
        try {
            runDemo(args);
        } catch (Throwable t) {
            exitCode = 1;
            Log.INSTANCE.e(t, MAIN_TAG, () -> "Demo run failed");
        }
        // The optional desktop keyring/DBus integration can leave helper threads behind on Linux.
        // Explicit process termination keeps the sample deterministic when run via Gradle.
        System.exit(exitCode);
    }

    /**
     * Executes the actual demo flow and leaves process lifecycle handling to {@link #main(String[])}.
     *
     * @param args optional CLI arguments, where the first argument points to a properties file
     */
    private static void runDemo(String[] args) {
        // get the configuration properties
        String propertiesFilename = getFilenameFromArgs(args);
        Properties props = loadProperties(propertiesFilename);

        // optional PoPP token
        String poppToken = getArg(props, POPP_TOKEN);
        Map<String,String> headers = new HashMap<>();
        if (poppToken != null) {
            headers.put(POPP_TOKEN_HEADER_NAME, poppToken);
        }

        boolean disableServerValidation = "true".equalsIgnoreCase(getArg(props, DISABLE_SERVER_VALIDATION));
        var argProd = getArg(props, ASL_PROD);
        boolean aslProdEnv = argProd == null || "true".equalsIgnoreCase(argProd);
        // Build the SDK once and reuse it for both the WebSocket and HTTP examples.
        ZetaSdkClient sdkClient = ZetaSdk.INSTANCE.build(
            getFirstResourceUrl(props),
            new BuildConfig(
                "demo-client",
                "0.5.0",
                "sdk-client",
                new StorageConfig(),
                new TpmConfig() {
                },
                new AuthConfig(
                    List.of(
                        "zero:audience"
                    ),
                    30,
                    aslProdEnv,
                    getTokenProvider(props),
                    AttestationConfig.software()
                ),
                getPlatformProductId(),
                new ZetaHttpClientBuilder("").disableServerValidation(disableServerValidation).logging(LogLevel.ALL, System.out::println),
                null,
                null
            ));

        ZetaHttpClient httpClient = null;
        try {
            // Forget any previous instance keys before the demo provisions fresh credentials.
            ZetaSdkClientExtension.forget(sdkClient);

            // Create an HttpClient instance from the ZetaSdkClient.
            httpClient = sdkClient.httpClient(it -> {
                it.logging(LogLevel.ALL, System.out::println);
                it.disableServerValidation(disableServerValidation);
                return Unit.INSTANCE;
            });

            // Forget any previous instance keys etc.
            ZetaSdkClientExtension.forget(sdkClient);

            // Run the parallel WebSocket CRUD example before the plain HTTP example.
            testParallelWebSocketConnections(sdkClient, props, headers);

            HttpClientExtension.getAsync(httpClient, "hellozeta", headers)
                .thenCompose(HttpClientExtension::bodyAsText)
                .whenComplete((body, ex)  -> {
                    if (ex != null){
                        Log.INSTANCE.e(ex, "Http", () -> "Http Get failed");
                    }
                    else {
                        Log.INSTANCE.i(null, "Http", () -> "Body:" + body);
                    }
                }).join();
        } finally {
            if (httpClient != null) {
                httpClient.close();
            }
            ZetaSdkClientExtension.close(sdkClient);
        }
    }

    /**
     * get the platform-specific product Id
     * @return PlatformProductId
     */
    private static PlatformProductId getPlatformProductId() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return new PlatformProductId.WindowsProductId("windows", "storeId", "");
        } else if (os.contains("mac")) {
            return new PlatformProductId.AppleProductId("apple", "macos", List.of());
        } else if (os.contains("nux")) {
            return new PlatformProductId.LinuxProductId("linux", "storeId", "", "");
        }
        throw new RuntimeException("Unsupported OS: " + os);
    }

    /**
     * Get the properties file filename from the run parameters.
     * @param args command line arguments
     * @return filename as taken from params
     */
    private static String getFilenameFromArgs(String[] args) {
        if (args.length > 0) {
            return args[0];
        }
        return null;
    }

    /**
     * Get the first resource URL from the configuration of "ENVIRONMENTS", or if lacking this,
     * try the "FACHDIENST_URL" configuration instead
     *
     * @param props the Properties file that override environment vars
     * @return first resource URL found in props or environment
     */
    private static String getFirstResourceUrl(Properties props) {

        String environments = getArg(props, ENVIRONMENTS);
        if (environments == null) {
            environments = getArg(props, FACHDIENST_URL);
        }
        if (environments == null) {
            Log.INSTANCE.e(null, MAIN_TAG, () -> "The configuration has not defined any environments / resource servers (" + ENVIRONMENTS + ")");
            throw new RuntimeException("The configuration has not defined any environments / resource servers (" + ENVIRONMENTS + ")");
        }
        StringTokenizer tok = new StringTokenizer(environments, " ");
        return tok.nextToken();
    }

    /**
     * Get a TokenProvider using the Properties given for configuration
     * If an SMB_KEYSTORE_FILE is given, SMB is used for configuration,
     * otherwise the SMCB configuration is used.
     *
     * @param props the Properties file that override environment vars
     * @return SubjectTokenProvider the subject token provider to use
     */
    private static SubjectTokenProvider getTokenProvider(Properties props) {

        String keystoreFile = getArg(props, SMB_KEYSTORE_FILE);
        if (keystoreFile != null) {
            String alias = getArg(props, SMB_KEYSTORE_ALIAS);
            String password = getArg(props, SMB_KEYSTORE_PASSWORD);

            return new SmbTokenProvider(new SmbTokenProvider.Credentials(keystoreFile, alias, password, ""));
        }
        String connectorUrl = getArg(props, SMCB_BASE_URL);
        if (connectorUrl != null) {
            String mandantId = getArg(props, SMCB_MANDANT_ID);
            String clientSystemId = getArg(props, SMCB_CLIENT_SYSTEM_ID);
            String workplaceId = getArg(props, SMCB_WORKSPACE_ID);
            String userId = getArg(props, SMCB_USER_ID);
            String cartHandle = getArg(props, SMCB_CARD_HANDLE);

            SmcbTokenProvider.ConnectorConfig config = new SmcbTokenProvider.ConnectorConfig(connectorUrl, mandantId, clientSystemId, workplaceId, userId, cartHandle);
            return new SmcbTokenProvider(config, new ConnectorApiImpl(config));
        }
        return new SmbTokenProvider(new SmbTokenProvider.Credentials("","","", ""));
    }

    /**
     * Get the value of a property, either from the given Properties object (with priority)
     * or from an environment variable
     *
     * @param props the Properties file that override environment vars
     * @param name the name of the parameter
     * @return property value or null if not found
     */
    private static String getArg(Properties props, String name) {
        String val = null;
        if (props != null) {
            val = props.getProperty(name);
        }
        if (val == null) {
            val = System.getenv(name);
        }
        return val;
    }

    /**
     * Returns a Properties object, either empty or filled with the contents of the given filename
     * Throws an exception if the file is not found,
     * null filename returns an empty Properties
     *
     * @param filename filename to load properties from or null
     * @return Properties the Properties file that override environment vars
     */
    private static Properties loadProperties(String filename) {
        Properties props = new Properties();
        if (filename != null) {
            Log.INSTANCE.i(null, MAIN_TAG, () -> "Loading properties from '" + filename + "'");
            try (FileInputStream input = new FileInputStream(filename)) {
                props.load(input);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            Log.INSTANCE.w(null, MAIN_TAG, () -> "No name for properties file given. Expecting configuration from environment variables");

        }
        return props;
    }

    /**
     * Runs the CRUD demo concurrently across multiple WebSocket sessions.
     *
     * <p>Each session uses its own prescription identifier and waits for its own queue messages,
     * which keeps the run deterministic even though broadcast topic traffic is interleaved.
     *
     * @param sdkClient SDK client used to create authenticated WebSocket sessions
     * @param props configuration source
     * @param headers request headers, including optional PoPP
     */
    public static void testParallelWebSocketConnections(
        ZetaSdkClient sdkClient,
        Properties props,
        Map<String, String> headers) {

        String baseUrl = getFirstResourceUrl(props);
        String wsUrl = toWsUrl(baseUrl, "ws");
        String host = extractHost(baseUrl);
        String contextPath = requiredArg(props, WS_SERVER_CONTEXT_PATH);
        boolean disableServerValidation =
            "true".equalsIgnoreCase(getArg(props, DISABLE_SERVER_VALIDATION));

        int sessionCount = 3;
        List<CompletableFuture<Unit>> sessions = new ArrayList<>();

        for (int i = 0; i < sessionCount; i++) {
            final int sessionId = i;
            final String tag = WEBSOCKETS_TAG + "-" + sessionId;

            CompletableFuture<Unit> future = WsClientAsyncExtension.wsAsync(
                sdkClient,
                wsUrl,
                builder -> {
                    builder.disableServerValidation(disableServerValidation);
                    builder.logging(LogLevel.ALL, msg ->
                        System.out.println("[session-" + sessionId + "] " + msg));
                    return Unit.INSTANCE;
                },
                headers,
                session -> runSessionWorkflow(session, host, contextPath, sessionId, tag)
            ).handle((res, ex) -> {
                if (isExpectedCloseCancellation(ex)) {
                    Log.INSTANCE.i(null, WEBSOCKETS_TAG, () ->
                        "[session-" + sessionId + "] Finished after expected close");
                    return Unit.INSTANCE;
                }
                if (ex != null) {
                    Log.INSTANCE.e(ex, WEBSOCKETS_TAG, () ->
                        "[session-" + sessionId + "] Failed");
                    throw new CompletionException(ex);
                }
                Log.INSTANCE.i(null, WEBSOCKETS_TAG, () ->
                    "[session-" + sessionId + "] Finished");
                return res;
            });

            sessions.add(future);
        }

        CompletableFuture.allOf(sessions.toArray(new CompletableFuture[0]))
            .whenComplete((v, ex) -> {
                if (ex != null) {
                    Log.INSTANCE.e(ex, WEBSOCKETS_TAG, () -> "One or more parallel sessions failed");
                } else {
                    Log.INSTANCE.i(null, WEBSOCKETS_TAG, () ->
                        "All " + sessionCount + " parallel sessions completed successfully");
                }
            })
            .join();
    }

    /**
     * Runs the CRUD demo with a single WebSocket session.
     *
     * @param sdkClient SDK client used to create the authenticated WebSocket session
     * @param props configuration source
     * @param headers request headers, including optional PoPP
     */
    public static void testWebSocketConnection(ZetaSdkClient sdkClient, Properties props, Map<String, String> headers) {
        String baseUrl = getFirstResourceUrl(props);
        String wsUrl = toWsUrl(baseUrl, "ws");
        String host = extractHost(baseUrl);
        String contextPath = requiredArg(props, WS_SERVER_CONTEXT_PATH);
        boolean disableServerValidation = "true".equalsIgnoreCase(getArg(props, DISABLE_SERVER_VALIDATION));

        WsClientAsyncExtension.wsAsync(
                sdkClient,
                wsUrl,
                builder -> {
                    builder.disableServerValidation(disableServerValidation);
                    builder.logging(LogLevel.ALL, System.out::println);
                    return Unit.INSTANCE;
                },
                headers,
                session -> runSessionWorkflow(session, host, contextPath, 0, WEBSOCKETS_TAG)
            ).handle((res, ex) -> {
                if (isExpectedCloseCancellation(ex)) {
                    Log.INSTANCE.i(null, WEBSOCKETS_TAG, () -> "WebSocket finished after expected close");
                    return Unit.INSTANCE;
                }
                if (ex != null) {
                    Log.INSTANCE.e(ex, WEBSOCKETS_TAG, () -> "WebSocket failed");
                    throw new CompletionException(ex);
                }
                Log.INSTANCE.i(null, WEBSOCKETS_TAG, () -> "WebSocket finished");
                return res;
            })
            .join();
    }

    /**
     * Opens the STOMP session and subscribes to the broadcast and per-user queues.
     *
     * @param session active WebSocket session
     * @param host STOMP host header value
     * @param contextPath service context path
     * @param events queue used for parsed incoming frames
     * @param tag logger tag for the current session
     * @return completed future once the subscriptions are active
     */
    private static CompletableFuture<Unit> connectAndSubscribe(
        WsClientAsyncExtension.WsAsyncSession session,
        String host,
        String contextPath,
        BlockingQueue<StompEvent> events,
        String tag
    ) {
        session.sendTextAsync(stompConnectFrame(host)).join();
        awaitConnected(events, tag);
        session.sendTextAsync(stompSubscribeFrame("sub-1", contextPath + "/topic/erezept")).join();
        session.sendTextAsync(stompSubscribeFrame("sub-2", contextPath + "/user/queue/erezept")).join();
        Log.INSTANCE.i(null, tag, () -> "Connected + subscribed");
        return CompletableFuture.completedFuture(Unit.INSTANCE);
    }

    /**
     * Executes the minimal CRUD verification for one session.
     *
     * <p>The flow sends {@code create}, waits for the per-user response, extracts the numeric
     * entity id returned by the server, and then reads that exact id back.
     *
     * @param session active WebSocket session
     * @param contextPath service context path
     * @param sessionId numeric session identifier for logging and unique payload generation
     * @param events queue used for parsed incoming frames
     * @param tag logger tag for the current session
     * @return completed future once the read roundtrip succeeded
     */
    private static CompletableFuture<Unit> sendPrescriptionCommands(
        WsClientAsyncExtension.WsAsyncSession session,
        String contextPath,
        int sessionId,
        BlockingQueue<StompEvent> events,
        String tag
    ) {
        String prescriptionId = buildPrescriptionId(sessionId);
        String createBody = buildCreateBody(prescriptionId);

        session.sendTextAsync(stompSendFrame(contextPath + "/app/erezept.create", createBody)).join();
        StompFrame createResponse = awaitQueueMessage(events, contextPath, tag, "create");
        long createdId = extractNumericId(createResponse.body);
        Log.INSTANCE.i(null, tag, () ->
            "Create response returned id=" + createdId + " for prescriptionId=" + prescriptionId);

        session.sendTextAsync(stompSendFrame(contextPath + "/app/erezept.read." + createdId, "{}")).join();
        StompFrame readResponse = awaitQueueMessage(events, contextPath, tag, "read");
        long readId = extractNumericId(readResponse.body);
        if (readId != createdId) {
            throw new IllegalStateException("Read response id mismatch: expected " + createdId + " but got " + readId);
        }

        Log.INSTANCE.i(null, tag, () ->
            "CRUD read succeeded for id=" + createdId + " and prescriptionId=" + prescriptionId);
        return CompletableFuture.completedFuture(Unit.INSTANCE);
    }

    /**
     * Registers the message listener and runs the full WebSocket workflow for one session.
     *
     * @param session active WebSocket session
     * @param host STOMP host header value
     * @param contextPath service context path
     * @param sessionId numeric session identifier
     * @param tag logger tag for the current session
     * @return future that completes when the session finished its CRUD roundtrip
     */
    private static CompletableFuture<Unit> runSessionWorkflow(
        WsClientAsyncExtension.WsAsyncSession session,
        String host,
        String contextPath,
        int sessionId,
        String tag
    ) {
        BlockingQueue<StompEvent> events = new LinkedBlockingQueue<>();
        session.onMessageAsync(new QueueingMessageListener(events, tag));

        return CompletableFuture.runAsync(() -> {
            try {
                connectAndSubscribe(session, host, contextPath, events, tag).join();
                sendPrescriptionCommands(session, contextPath, sessionId, events, tag).join();
            } finally {
                if (session.isActive()) {
                    session.closeAsync().join();
                }
            }
        }).thenApply(v -> Unit.INSTANCE);
    }

    /**
     * Waits until the STOMP broker confirms the connection.
     *
     * @param events queue used for parsed incoming frames
     * @param tag logger tag for the current session
     */
    private static void awaitConnected(BlockingQueue<StompEvent> events, String tag) {
        StompFrame connected = awaitFrame(events, tag, "CONNECTED", frame ->
            "CONNECTED".equals(frame.command));
        Log.INSTANCE.i(null, tag, () -> "STOMP connected: " + connected.command);
    }

    /**
     * Waits for the next successful message on the per-user CRUD queue.
     *
     * <p>The shared topic receives broadcasts from all sessions, so CRUD correlation happens only
     * on the user queue.
     *
     * @param events queue used for parsed incoming frames
     * @param contextPath service context path
     * @param tag logger tag for the current session
     * @param operation operation name used in log and error messages
     * @return parsed STOMP frame for the successful user-queue response
     */
    private static StompFrame awaitQueueMessage(
        BlockingQueue<StompEvent> events,
        String contextPath,
        String tag,
        String operation
    ) {
        String expectedDestination = contextPath + "/user/queue/erezept";
        return awaitFrame(events, tag, operation, frame -> {
            if (!"MESSAGE".equals(frame.command)) {
                return false;
            }
            if (!expectedDestination.equals(frame.headers.get("destination"))) {
                return false;
            }
            Integer errorStatus = extractErrorStatus(frame.body);
            if (errorStatus != null) {
                throw new IllegalStateException(
                    "Received error response for " + operation + ": " + frame.body);
            }
            return extractNumericId(frame.body) >= 0;
        });
    }

    /**
     * Polls the event queue until a frame matches the provided predicate or a timeout expires.
     *
     * @param events queue used for parsed incoming frames
     * @param tag logger tag for the current session
     * @param waitLabel label used in timeout and diagnostic messages
     * @param predicate frame matcher
     * @return the first matching frame
     */
    private static StompFrame awaitFrame(
        BlockingQueue<StompEvent> events,
        String tag,
        String waitLabel,
        java.util.function.Predicate<StompFrame> predicate
    ) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadlineNanos) {
            StompEvent event;
            try {
                event = events.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for " + waitLabel, e);
            }

            if (event == null) {
                continue;
            }
            if (event.error != null) {
                throw new RuntimeException("WebSocket session failed while waiting for " + waitLabel, event.error);
            }
            if (event.closed) {
                throw new IllegalStateException("WebSocket closed while waiting for " + waitLabel);
            }
            if (event.frame == null) {
                continue;
            }
            if (predicate.test(event.frame)) {
                return event.frame;
            }
            Log.INSTANCE.i(null, tag, () ->
                "Ignoring STOMP frame while waiting for " + waitLabel + ": "
                    + event.frame.command + " "
                    + event.frame.headers.getOrDefault("destination", ""));
        }
        throw new IllegalStateException("Timed out while waiting for " + waitLabel);
    }

    /**
     * Builds a unique prescription identifier for a single demo session.
     *
     * @param sessionId numeric session identifier
     * @return unique prescription identifier
     */
    private static String buildPrescriptionId(int sessionId) {
        return "RX-2025-" + sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Creates the JSON payload used for the demo {@code erezept.create} request.
     *
     * @param prescriptionId unique prescription identifier
     * @return JSON request body
     */
    private static String buildCreateBody(String prescriptionId) {
        return """
            {
              "prescriptionId": "%s",
              "patientId": "PAT-123456",
              "practitionerId": "PRAC-98765",
              "medicationName": "Ibuprofen 400 mg",
              "dosage": "1",
              "issuedAt": "2025-09-22T10:30:00Z",
              "expiresAt": "2025-12-31T23:59:59Z",
              "status": "CREATED"
            }
            """.formatted(prescriptionId).trim();
    }

    /**
     * Extracts an error status from a JSON body when the server returned an error envelope.
     *
     * @param body JSON body
     * @return HTTP-style status code or {@code null} if the body does not contain one
     */
    private static Integer extractErrorStatus(String body) {
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\"status\"\\s*:\\s*(\\d{3})").matcher(body);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    /**
     * Extracts the numeric entity id from a CRUD response body.
     *
     * @param body JSON body
     * @return numeric entity id
     */
    private static long extractNumericId(String body) {
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("No numeric id in payload: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }

    /**
     * Detects the coroutine cancellation emitted by the WebSocket wrapper when the client closes
     * the session itself after a successful run.
     *
     * @param throwable exception to inspect
     * @return {@code true} when the throwable chain contains the expected close cancellation
     */
    private static boolean isExpectedCloseCancellation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if ("kotlinx.coroutines.JobCancellationException".equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Parses a raw STOMP frame into command, headers, and body components.
     *
     * @param text raw frame text
     * @return parsed STOMP frame or {@code null} for empty keep-alive content
     */
    private static StompFrame parseStompFrame(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.endsWith("\u0000")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int bodySeparator = normalized.indexOf("\n\n");
        String head = bodySeparator >= 0 ? normalized.substring(0, bodySeparator) : normalized;
        String body = bodySeparator >= 0 ? normalized.substring(bodySeparator + 2) : "";

        String[] lines = head.split("\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return new StompFrame(lines[0], headers, body);
    }

    /**
     * Converts an HTTP(S) base URL into the matching WebSocket URL.
     *
     * @param baseUrl HTTP(S) base URL
     * @param path path suffix to append
     * @return WebSocket URL
     */
    private static String toWsUrl(String baseUrl, String path) {
        String wsBase = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://");
        return wsBase + path;
    }

    /**
     * Extracts the host component used in the STOMP {@code host} header.
     *
     * @param url source URL
     * @return host portion of the URL
     */
    private static String extractHost(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves a required configuration value and fails fast when it is missing.
     *
     * @param props property source
     * @param name configuration key
     * @return resolved configuration value
     */
    private static String requiredArg(Properties props, String name) {
        String v = getArg(props, name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing required config: " + name);
        return v;
    }

    /**
     * Listener that parses incoming STOMP frames and forwards them into the per-session queue.
     */
    private static final class QueueingMessageListener
        implements WsClientAsyncExtension.WsAsyncSession.WsMessageListener {
        private final BlockingQueue<StompEvent> events;
        private final String tag;

        private QueueingMessageListener(BlockingQueue<StompEvent> events, String tag) {
            this.events = events;
            this.tag = tag;
        }

        @Override
        public void onText(String text) {
            Log.INSTANCE.i(null, tag, () -> text.startsWith("CONNECTED") ? text : "Frame:\n" + text);
            StompFrame frame = parseStompFrame(text);
            if (frame != null) {
                events.offer(StompEvent.frame(frame));
            }
        }

        @Override
        public void onBinary(byte[] bytes) {
            Log.INSTANCE.i(null, tag, () -> "Binary: " + bytes.length + " bytes");
        }

        @Override
        public void onClose() {
            Log.INSTANCE.i(null, tag, () -> "Closed");
            events.offer(StompEvent.closed());
        }

        @Override
        public void onError(Throwable error) {
            Log.INSTANCE.e(error, tag, () -> "Error");
            events.offer(StompEvent.error(error));
        }
    }

    /**
     * Small wrapper representing one event emitted by the WebSocket listener.
     */
    private static final class StompEvent {
        private final StompFrame frame;
        private final Throwable error;
        private final boolean closed;

        private StompEvent(StompFrame frame, Throwable error, boolean closed) {
            this.frame = frame;
            this.error = error;
            this.closed = closed;
        }

        private static StompEvent frame(StompFrame frame) {
            return new StompEvent(frame, null, false);
        }

        private static StompEvent error(Throwable error) {
            return new StompEvent(null, error, false);
        }

        private static StompEvent closed() {
            return new StompEvent(null, null, true);
        }
    }

    /**
     * Parsed representation of a STOMP frame.
     */
    private static final class StompFrame {
        private final String command;
        private final Map<String, String> headers;
        private final String body;

        private StompFrame(String command, Map<String, String> headers, String body) {
            this.command = command;
            this.headers = headers;
            this.body = body;
        }
    }
}


