package ch.admin.bit.jeap.jme.mcp.test;

import ch.admin.bit.jeap.jme.mcp.anonymous.JmeMcpAnonymousServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots jme-mcp-anonymous-service's own application class and tools for real (both live in
 * ch.admin.bit.jeap.jme.mcp.anonymous, so the default component scan of its {@code @SpringBootApplication}
 * class picks them up). No resource server is configured here either, matching the real service: there is
 * nothing to issue or validate a token with, so no {@code McpTestSupportConfiguration} JWKS endpoint is
 * imported, and no process needs to be running to run this.
 *
 * <p>Shows that jeap.mcp.security.anonymous-access, with no resource server at all, means every caller is
 * permanently anonymous: a tool declaring {@code permitAll()} answers, one that still requires a role is
 * permanently {@code FORBIDDEN} -- including when a (meaningless, since nothing can ever validate it) bearer
 * token happens to be present.
 */
@SpringBootTest(classes = JmeMcpAnonymousServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "spring.application.name=jme-mcp-anonymous-service",
                "spring.ai.mcp.server.name=jme-mcp-anonymous-service",
                "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.protocol=STATELESS",
                "spring.ai.mcp.server.stateless.mcp-endpoint=/mcp",
                "spring.ai.mcp.server.annotation-scanner.enabled=true",
                // Excludes the permit-all chain of jeap-spring-boot-security-starter-test: without this, that
                // starter's own test-only PermitAllWebSecurityConfiguration would let every request through
                // regardless of the real security chain under test here, and this test would pass for the
                // wrong reason.
                "spring.autoconfigure.exclude=ch.admin.bit.jeap.security.test.resource.configuration.PermitAllWebSecurityConfiguration",
                "jeap.mcp.security.anonymous-access=true",
                // jme-mcp-authenticated-service's own application.yml (which does set a real issuer) is also on
                // this module's test classpath, since jme-mcp-test depends on that module too; without this
                // explicit override, its issuer could leak in via classpath merging and make this test
                // accidentally run with a resource server "configured" after all.
                "jeap.security.oauth2.resourceserver.authorization-server.issuer="
        })
class AnonymousServiceIT {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anonymousCallToAPublicToolSucceeds() throws Exception {
        HttpResponse<String> response = post(null, toolCall("is_public_holiday", "{\"date\":\"2026-12-25\"}"));
        assertThat(response.statusCode()).isEqualTo(200);
        // contains("true") alone would also match a failed call's "isError":true (see
        // toolRequiringARoleIsPermanentlyForbidden below -- a denied call is still HTTP 200), so this couldn't
        // have failed if anonymous access to a permitAll() tool broke. Assert both.
        assertThat(response.body()).contains("isError\":false", "\"text\":\"true\"");
    }

    @Test
    void secondPublicToolAlsoAnswersAnonymously() throws Exception {
        HttpResponse<String> response = post(null, toolCall("get_current_time", "{}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("isError\":false");
    }

    @Test
    void toolRequiringARoleIsPermanentlyForbidden() throws Exception {
        HttpResponse<String> response = post(null, toolCall("whoami", "{}"));
        assertThat(response.statusCode()).isEqualTo(200); // the HTTP request succeeds, the tool call itself fails
        assertThat(response.body()).contains("FORBIDDEN");
    }

    @Test
    void secondRoleRequiringToolIsAlsoPermanentlyForbidden() throws Exception {
        HttpResponse<String> response = post(null, toolCall("convert_temperature", "{\"value\":0,\"fromUnit\":\"C\",\"toUnit\":\"F\"}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("FORBIDDEN");
    }

    @Test
    void aPresentBearerTokenChangesNothingSinceThereIsNothingToValidateItWith() throws Exception {
        // Not a real JWT: with no resource server configured, no filter ever inspects the header at all, so a
        // well-formed token would be treated exactly the same way -- still just anonymous, not rejected either.
        HttpResponse<String> response = post("not-a-jwt", toolCall("whoami", "{}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("FORBIDDEN");
    }

    private static String baseUrl() {
        return "http://localhost:" + PORT;
    }

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
