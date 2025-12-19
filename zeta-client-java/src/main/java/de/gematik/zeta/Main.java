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
import de.gematik.zeta.sdk.attestation.model.PlatformProductId;
import de.gematik.zeta.sdk.authentication.AuthConfig;
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider;
import de.gematik.zeta.sdk.attestation.model.ClientSelfAssessment;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import static de.gematik.zeta.sdk.WsClientExtensionKt.stompConnectFrame;
import static de.gematik.zeta.sdk.WsClientExtensionKt.stompSendFrame;
import static de.gematik.zeta.sdk.WsClientExtensionKt.stompSubscribeFrame;

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
    public static final String POPP_TOKEN = "POPP_TOKEN";
    public static final String POPP_TOKEN_HEADER_NAME = "PoPP";
    public static final String WS_SERVER_CONTEXT_PATH = "WS_SERVER_CONTEXT_PATH";

    public static final String WEBSOCKETS_TAG = "Websockets";
    public static final String MAIN_TAG = "Main";

    // This java client just demonstrates how to use the kotlin ZETA API, and not suitable for production
    @SuppressWarnings("java:S4507")
    public static void main(String[] args) {
        Log.INSTANCE.initDebugLogger();
        Log.INSTANCE.i(null, MAIN_TAG,() -> "Hello and welcome!");

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
        // create a ZetaSdkClient instance using the configuration items given
        ZetaSdkClient sdkClient = ZetaSdk.INSTANCE.build(
            getFirstResourceUrl(props),
            new BuildConfig(
                "demo-client",
                "0.2.0",
                "sdk-client",
                new StorageConfig(),
                new TpmConfig() {
                },
                new AuthConfig(
                    List.of(
                        "zero:audience"
                    ),
                    30,
                    true,
                    getTokenProvider(props)
                ),
                new ClientSelfAssessment("name", "clientId", "manufacturerId", "manufacturerName", "test@manufacturertestmail.de", 0, new PlatformProductId.AppleProductId("apple","macos", List.of("bundleX"))),
                new ZetaHttpClientBuilder("").disableServerValidation(disableServerValidation).logging(LogLevel.ALL, System.out::println),
                null,
                null
            ));

        // forget any previous instance keys etc
        ZetaSdkClientExtension.forget();

        // create an HttpClient instance from the ZetaSdkClient
        ZetaHttpClient httpClient = sdkClient.httpClient(it -> {
            it.logging(LogLevel.ALL, System.out::println);
            it.disableServerValidation(disableServerValidation);
            return Unit.INSTANCE;
        });

        // forget any previous instance keys etc
        ZetaSdkClientExtension.forget();

        // Test WebSockets
        testWebSocketConnection(sdkClient, props, headers);

        // Test HttpGet
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

            return new SmbTokenProvider(new SmbTokenProvider.Credentials(keystoreFile, alias, password));
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
        return new SmbTokenProvider(new SmbTokenProvider.Credentials("","",""));
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

    private static void testWebSocketConnection(ZetaSdkClient sdkClient, Properties props, Map<String, String> headers) {
        String baseUrl = getFirstResourceUrl(props);
        String contextPath = getArg(props, WS_SERVER_CONTEXT_PATH);
        String wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://") + "/ws";
        String host = extractHost(baseUrl);

        try {
            WsClientExtension.ws(sdkClient, wsUrl,
                builder -> {
                    builder.disableServerValidation("true".
                        equalsIgnoreCase(getArg(props, DISABLE_SERVER_VALIDATION)));
                    return Unit.INSTANCE;
                },
                headers, session -> {
                connectAndSubscribe(session, host, contextPath);
                sendPrescriptionCommands(session, contextPath);
                receiveMessages(session);
                session.close();
            });
        } catch (Exception ex) {
            Log.INSTANCE.e(ex, WEBSOCKETS_TAG, () -> "Websocket failed");
        }
    }

    private static String extractHost(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static void connectAndSubscribe(WsClientExtension.WsSession session, String host, String contextPath) {
        session.sendText(stompConnectFrame(host));

        WsClientExtension.WsMessage msg = session.receiveNext();
        if (msg instanceof WsClientExtension.WsMessage.Text textMsg) {
            Log.INSTANCE.i(null, WEBSOCKETS_TAG, () -> "CONNECTED frame:\n" + textMsg.getText());
        }

        session.sendText(stompSubscribeFrame("sub-1", contextPath + "/topic/erezept"));
        session.sendText(stompSubscribeFrame("sub-2", contextPath + "/user/queue/erezept"));
    }

    private static void sendPrescriptionCommands(WsClientExtension.WsSession session, String contextPath) {
        String createBody = """
    {
      "prescriptionId": "RX-2025-100123",
      "patientId": "PAT-123456",
      "practitionerId": "PRAC-98765",
      "medicationName": "Ibuprofen 400 mg",
      "dosage": "1",
      "issuedAt": "2025-09-22T10:30:00Z",
      "expiresAt": "2025-12-31T23:59:59Z",
      "status": "CREATED"
    }
    """.trim();

        session.sendText(stompSendFrame(contextPath + "/app/erezept.create", createBody));
        session.sendText(stompSendFrame(contextPath + "/app/erezept.read.1", "{}"));
    }

    private static void receiveMessages(WsClientExtension.WsSession session) {
        for (int i = 0; i < 10; i++) {
            WsClientExtension.WsMessage incoming = session.receiveNext();
            if (incoming == null || incoming instanceof WsClientExtension.WsMessage.Close) {
                Log.INSTANCE.i(null, WEBSOCKETS_TAG, () -> "WebSocket closed.");
                break;
            }
            if (incoming instanceof WsClientExtension.WsMessage.Text text) {
                Log.INSTANCE.i(null, WEBSOCKETS_TAG, () -> "Received: " + text.getText());
            } else if (incoming instanceof WsClientExtension.WsMessage.Binary bin) {
                Log.INSTANCE.i(null, WEBSOCKETS_TAG, () -> "Received " + bin.getBytes().length + " bytes (binary)");
            }
        }
    }
}


