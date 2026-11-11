package org.openfilz.dms.security.session;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The realm a new installation imports must give a working Keycloak account console
 * ({@code /realms/<realm>/account}).
 * <p>
 * The export defines its own client scopes, so Keycloak gives the clients it creates at import
 * ({@code account}, {@code account-console}) no client scope at all, and the {@code roles} scope of
 * the export has no client-roles mapper. The account console then gets a token without
 * {@code resource_access.account.roles} and the account REST API answers 403 ("Something went
 * wrong"). The export therefore defines both clients itself: {@code account-console} alone clashes
 * with the one Keycloak creates together with {@code account}.
 */
class RealmImportAccountConsoleTest {

    /** Maven runs the tests from the module directory. */
    private static final Path REALM_IMPORT =
            Path.of("..", "deploy", "docker-compose", "dokploy", "keycloak", "import", "realm-export.json");

    private static JsonNode realm;

    @BeforeAll
    static void readRealm() throws Exception {
        assertTrue(Files.isRegularFile(REALM_IMPORT), "realm import not found at " + REALM_IMPORT.toAbsolutePath());
        realm = JsonMapper.builder().build().readTree(Files.readString(REALM_IMPORT));
    }

    private static JsonNode client(String clientId) {
        for (JsonNode c : realm.get("clients")) {
            if (clientId.equals(c.get("clientId").asString())) {
                return c;
            }
        }
        return null;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new HashSet<>();
        if (array != null) {
            for (JsonNode n : array) {
                out.add(n.asString());
            }
        }
        return out;
    }

    @Test
    void theExportDefinesBothAccountClients() {
        assertNotNull(client("account"), "the export must define the account client: without it Keycloak creates "
                + "account and account-console itself, with no client scope");
        assertNotNull(client("account-console"), "the export must define the account-console client");
    }

    @Test
    void theAccountConsoleTokenCarriesTheAccountRoles() {
        JsonNode console = client("account-console");
        assertNotNull(console);
        Set<String> scopes = strings(console.get("defaultClientScopes"));
        assertTrue(scopes.containsAll(Set.of("basic", "roles", "profile", "email")),
                "account-console default client scopes: " + scopes);

        boolean clientRoles = false;
        for (JsonNode m : console.path("protocolMappers")) {
            if ("oidc-usermodel-client-role-mapper".equals(m.path("protocolMapper").asString())) {
                JsonNode config = m.path("config");
                assertEquals("resource_access.${client_id}.roles", config.path("claim.name").asString());
                assertEquals("true", config.path("access.token.claim").asString());
                clientRoles = true;
            }
        }
        assertTrue(clientRoles, "account-console needs a client-roles mapper: the roles scope of the export "
                + "only maps realm roles, and the account REST API reads resource_access.account.roles");

        // Kept to the account roles mapped to it, as in a stock realm.
        assertFalse(console.path("fullScopeAllowed").asBoolean(true), "account-console must keep fullScopeAllowed off");
        boolean mapped = false;
        for (JsonNode m : realm.path("clientScopeMappings").path("account")) {
            if ("account-console".equals(m.path("client").asString())) {
                assertTrue(strings(m.get("roles")).contains("manage-account"), "account-console scope mapping: " + m);
                mapped = true;
            }
        }
        assertTrue(mapped, "clientScopeMappings.account must map manage-account to account-console");
    }

    @Test
    void newUsersStillGetTheAccountRoles() {
        // Keycloak adds them to the default roles only when it creates the account client itself.
        Set<String> accountRoles = new HashSet<>();
        for (JsonNode r : realm.path("roles").path("client").path("account")) {
            accountRoles.add(r.path("name").asString());
        }
        assertTrue(accountRoles.containsAll(Set.of("view-profile", "manage-account", "manage-account-links")),
                "roles.client.account: " + accountRoles);

        String defaultRole = realm.path("defaultRole").path("name").asString();
        JsonNode composites = null;
        for (JsonNode r : realm.path("roles").path("realm")) {
            if (defaultRole.equals(r.path("name").asString())) {
                composites = r.path("composites");
            }
        }
        assertNotNull(composites, "default role " + defaultRole + " not found in roles.realm");
        assertTrue(strings(composites.path("client").path("account")).containsAll(Set.of("view-profile", "manage-account")),
                defaultRole + " must keep the account roles view-profile and manage-account");
    }

    @Test
    void everyClientScopeAClientNamesIsDefined() {
        // A realm import silently drops a reference to a client scope the export does not define.
        Set<String> defined = new HashSet<>();
        for (JsonNode s : realm.path("clientScopes")) {
            defined.add(s.path("name").asString());
        }
        List<String> undefined = new ArrayList<>();
        for (JsonNode c : realm.get("clients")) {
            for (String list : List.of("defaultClientScopes", "optionalClientScopes")) {
                for (String s : strings(c.get(list))) {
                    if (!defined.contains(s)) {
                        undefined.add(c.get("clientId").asString() + " -> " + s);
                    }
                }
            }
        }
        assertTrue(undefined.isEmpty(), "client scopes named by a client but not defined in the export: " + undefined);
    }
}
