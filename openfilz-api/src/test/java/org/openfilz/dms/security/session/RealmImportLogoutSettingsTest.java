package org.openfilz.dms.security.session;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The realm a new installation imports. Keycloak never calls the back-channel logout URL of a
 * client whose "Front channel logout" switch is on, and a client that leaves the field out gets it
 * on: a client that declares the URL must declare the switch off, or a session signed out by a
 * password change keeps its access until its token expires.
 */
class RealmImportLogoutSettingsTest {

    /** Maven runs the tests from the module directory. */
    private static final Path REALM_IMPORT =
            Path.of("..", "deploy", "docker-compose", "dokploy", "keycloak", "import", "realm-export.json");

    @Test
    void aClientWithABackchannelLogoutUrlHasFrontChannelLogoutOff() throws Exception {
        assertTrue(Files.isRegularFile(REALM_IMPORT), "realm import not found at " + REALM_IMPORT.toAbsolutePath());
        JsonNode realm = JsonMapper.builder().build().readTree(Files.readString(REALM_IMPORT));

        List<String> notified = new ArrayList<>();
        for (JsonNode client : realm.get("clients")) {
            JsonNode url = client.path("attributes").path("backchannel.logout.url");
            if (url.isMissingNode() || url.asString().isBlank()) {
                continue;
            }
            String clientId = client.get("clientId").asString();
            notified.add(clientId);
            JsonNode frontChannel = client.get("frontchannelLogout");
            assertTrue(frontChannel != null && frontChannel.isBoolean() && !frontChannel.asBoolean(),
                    clientId + " declares a back-channel logout URL: it must also declare \"frontchannelLogout\": false, "
                            + "or Keycloak never calls that URL");
        }
        assertFalse(notified.isEmpty(), "no client of the realm import declares a back-channel logout URL");
    }
}
