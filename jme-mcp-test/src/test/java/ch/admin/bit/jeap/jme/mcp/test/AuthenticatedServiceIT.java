package ch.admin.bit.jeap.jme.mcp.test;

import ch.admin.bit.jeap.jme.mcp.authenticated.JmeMcpAuthenticatedServiceApplication;
import ch.admin.bit.jeap.mcp.test.McpTestClient;
import ch.admin.bit.jeap.mcp.test.McpTestSupportConfiguration;
import ch.admin.bit.jeap.mcp.test.McpTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots jme-mcp-authenticated-service's own application class and tools for real (both live in
 * ch.admin.bit.jeap.jme.mcp.authenticated, so the default component scan of its {@code @SpringBootApplication}
 * class picks them up), with a real signed-JWT JWKS endpoint from jeap-spring-boot-mcp-starter-test standing in
 * for jme-mcp-oauth-mock -- no external process needed to run this.
 *
 * <p>All configuration is given explicitly below rather than relying on the service's own bundled
 * application.yml, since jme-mcp-anonymous-service's application.yml is also on this module's test classpath.
 */
@SpringBootTest(classes = JmeMcpAuthenticatedServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "spring.application.name=jme-mcp-authenticated-service",
                "spring.ai.mcp.server.name=jme-mcp-authenticated-service",
                "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.protocol=STATELESS",
                "spring.ai.mcp.server.stateless.mcp-endpoint=/mcp",
                "spring.ai.mcp.server.annotation-scanner.enabled=true",
                // Excludes the permit-all chain of jeap-spring-boot-security-starter-test: without this, that
                // starter's own test-only PermitAllWebSecurityConfiguration would let every request through
                // regardless of the real security chain under test here, and this test would pass for the
                // wrong reason.
                "spring.autoconfigure.exclude=ch.admin.bit.jeap.security.test.resource.configuration.PermitAllWebSecurityConfiguration",
                "jeap.security.oauth2.resourceserver.authorization-server.issuer=http://localhost/auth",
                "jeap.mcp.security.resource-uri=http://localhost/mcp"
        })
@Import(McpTestSupportConfiguration.class)
class AuthenticatedServiceIT {

    // The resource server resolves its JWKS URI at startup, so the port must be known before the context starts
    private static final int PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
                () -> "http://localhost:" + PORT + "/mcp-test-jwks/jwks.json");
    }

    @Autowired
    private McpTestTokens tokens;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void requestWithoutTokenIsRejectedWith401() throws Exception {
        HttpResponse<String> response = post(null, toolCall("get_weather", "{\"city\":\"Bern\"}"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
    }

    @Test
    void readerCanCallEveryTool() {
        try (McpTestClient client = McpTestClient.connect(baseUrl(), "/mcp", tokens.withRoles("READER"))) {
            assertThat(client.listTools()).extracting(tool -> tool.name())
                    .contains("get_weather", "get_current_time", "convert_temperature");

            assertThat(client.call("get_weather", Map.of("city", "Bern")).text()).contains("°C");
            assertThat(client.call("get_current_time", Map.of()).text()).isNotBlank();
            assertThat(client.call("convert_temperature", Map.of("value", 100, "fromUnit", "C", "toUnit", "F")).text())
                    .isEqualTo("212.0");
        }
    }

    @Test
    void callerWithoutReaderRoleIsForbidden() {
        try (McpTestClient client = McpTestClient.connect(baseUrl(), "/mcp", tokens.withRoles("OTHER"))) {
            var result = client.call("get_weather", Map.of("city", "Bern"));
            assertThat(result.isError()).isTrue();
            assertThat(result.errorCode()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void expiredTokenIsRejectedWith401() throws Exception {
        HttpResponse<String> response = post(tokens.expired("READER"), toolCall("get_weather", "{\"city\":\"Bern\"}"));
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static String baseUrl() {
        return "http://localhost:" + PORT;
    }

    /** Raw JSON-RPC POST, for the no-token case a well-behaved client can't produce. */
    private HttpResponse<String> post(String bearerToken, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String toolCall(String tool, String arguments) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}""".formatted(tool, arguments);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
