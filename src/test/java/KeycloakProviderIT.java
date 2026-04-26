import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots Keycloak in a container with the freshly-built shaded JAR mounted as a provider
 * (mirroring how waldur-docker-compose deploys it), then queries the admin REST API
 * to confirm all three Waldur protocol mappers are registered.
 *
 * Skipped automatically when Docker is unavailable (e.g. CI without docker:dind), so it
 * never breaks `mvn install` runs that don't have a container runtime.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "plugin.jar", matches = ".*\\.jar")
class KeycloakProviderIT {

    private static final String JAR_PATH = System.getProperty("plugin.jar");
    // The default is set by the failsafe plugin via pom.xml (docker.registry.prefix property).
    // Falls back to the upstream image only when run outside Maven without -Dkeycloak.image.
    private static final String KEYCLOAK_IMAGE =
            System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:26.6.0");

    @Container
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(new File(JAR_PATH)));

    @BeforeAll
    static void verifyJarExists() {
        assertTrue(new File(JAR_PATH).isFile(),
                "Shaded plugin JAR not found at " + JAR_PATH + " — run `mvn package` before invoking ITs directly.");
    }

    @Test
    void waldurProtocolMappersAreRegistered() throws Exception {
        String authBase = KEYCLOAK.getAuthServerUrl();
        String adminToken = fetchAdminToken(authBase);
        JsonNode serverInfo = fetchServerInfo(authBase, adminToken);

        JsonNode protocolMappers = serverInfo.path("providers").path("protocol-mapper").path("providers");
        assertTrue(protocolMappers.isObject(),
                "Server info missing 'providers.protocol-mapper.providers'; got: " + serverInfo.path("providers").fieldNames());

        Iterator<String> names = protocolMappers.fieldNames();
        java.util.Set<String> registered = new java.util.HashSet<>();
        names.forEachRemaining(registered::add);

        assertTrue(registered.contains("oidc-waldurminiomapper"),
                "MinIO mapper not registered. Available: " + registered);
        assertTrue(registered.contains("oidc-waldur-offering-access-mapper"),
                "Offering access mapper not registered. Available: " + registered);
        assertTrue(registered.contains("oidc-waldurusernamemapper"),
                "Username mapper not registered. Available: " + registered);
    }

    private static String fetchAdminToken(String authBase) throws Exception {
        String body = "grant_type=password"
                + "&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(KEYCLOAK.getAdminUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(KEYCLOAK.getAdminPassword(), StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(authBase + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Failed to obtain admin token: " + resp.body());

        return new ObjectMapper().readTree(resp.body()).path("access_token").asText();
    }

    private static JsonNode fetchServerInfo(String authBase, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(authBase + "/admin/serverinfo"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "serverinfo request failed: " + resp.body());

        return new ObjectMapper().readTree(resp.body());
    }
}
