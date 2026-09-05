package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The MCP endpoint must apply the OpenFilz role model exactly as the REST API does.
 * <p>
 * <b>This is a regression test for a confirmed privilege escalation.</b> Roles used to be enforced
 * only by the HTTP security chain, whose authorization manager matches on request method and path
 * — and it is scoped to {@code /api/v1/**} plus the GraphQL path, so {@code /mcp} fell through to
 * a bare {@code authenticated()} with no role evaluation at all. Tools compound it: they call
 * {@code DocumentService} in-process on a tool thread, so no request is ever matched. Measured
 * against a live server, one READER-only token produced:
 * <pre>
 * POST /api/v1/folders                     -&gt; 403   (correct)
 * tools/call createFolder via POST /mcp    -&gt; folder created   (escalation)
 * </pre>
 * The fix lives in the tool layer ({@code DocumentAiTools} consults {@code AiToolRolePolicy}), so
 * the chat assistant is covered by the same rule; {@code McpToolCallbackProvider} refuses earlier
 * for a clearer message. This suite pins the MCP half end to end, over the real HTTP + JWT stack.
 * <p>
 * Note that a refused write still comes back as a normal tool result with {@code isError=false} —
 * a refusal is an answer for the calling agent, not a protocol fault. What matters is the message
 * and, above all, that nothing was created.
 *
 * @see org.openfilz.dms.service.ai.DefaultAiToolRolePolicyTest the capability → role mapping
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpRoleEnforcementIT extends AbstractMcpIT {

    private static final String READER = "reader-user";
    private static final String CONTRIBUTOR = "contributor-user";

    public McpRoleEnforcementIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    /** READ_WRITE deliberately: the point is that the *mode* allows writes and the *role* does not. */
    @DynamicPropertySource
    static void useReadWriteMode(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
    }

    /** Swap the bearer token the inherited JSON-RPC helper uses. */
    private void as(String username) {
        accessToken = getAccessToken(username);
    }

    @Test
    @DisplayName("a READER may search and read through MCP")
    void readerMaySearch() {
        as(READER);

        String result = callToolText("queryDocuments", """
                {"sortBy":"updatedAt","sortOrder":"DESC","pageSize":5,"countOnly":false}""");

        assertThat(result)
                .as("READER holds the read role, so search must work exactly as it does over REST")
                .doesNotContain("Not permitted");
    }

    @Test
    @DisplayName("a READER is refused a write, and nothing is created")
    void readerMayNotWrite() {
        String folderName = "mcp-role-" + UUID.randomUUID().toString().substring(0, 8);

        as(READER);
        String refusal = callToolText("createFolder", """
                {"name":"%s"}""".formatted(folderName));

        assertThat(refusal)
                .as("""
                        A READER created a folder through MCP that the same token is refused over \
                        REST with 403. If this assertion fails the escalation is back.""")
                .contains("Not permitted");

        // The assertion that actually matters: no side effect reached storage. Checked as a
        // CONTRIBUTOR so a read-scope difference cannot hide a folder that was really created.
        as(CONTRIBUTOR);
        String found = callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FOLDER","pageSize":10}""".formatted(folderName));

        assertThat(found)
                .as("a refused write must not have created anything")
                .doesNotContain(folderName);
    }

    @Test
    @DisplayName("every mutating tool is refused for a READER")
    void readerIsRefusedEveryMutatingTool() {
        // Not just createFolder: a gate applied to one tool and forgotten on the others is the
        // likeliest way for this to regress.
        as(READER);
        String probe = "mcp-role-" + UUID.randomUUID().toString().substring(0, 8);

        assertThat(callToolText("writeFile", """
                {"fileName":"%s.txt","content":"nope"}""".formatted(probe))).contains("Not permitted");
        assertThat(callToolText("moveDocuments", """
                {"documentNames":"%s.txt","targetFolder":"%s"}""".formatted(probe, probe))).contains("Not permitted");
        assertThat(callToolText("renameDocument", """
                {"documentName":"%s.txt","newName":"%s-x.txt"}""".formatted(probe, probe))).contains("Not permitted");
    }

    @Test
    @DisplayName("getDocumentActivity is gated on the AUDITOR role, like /api/v1/audit")
    void auditTrailToolIsGatedOnAuditor() {
        as(READER);
        assertThat(callToolText("getDocumentActivity", """
                {"document":"%s"}""".formatted(UUID.randomUUID()))).contains("Not permitted");
    }

    @Test
    @DisplayName("a CONTRIBUTOR may write, so the gate is not simply refusing everyone")
    void contributorMayWrite() {
        String folderName = "mcp-role-ok-" + UUID.randomUUID().toString().substring(0, 8);

        as(CONTRIBUTOR);
        String created = callToolText("createFolder", """
                {"name":"%s"}""".formatted(folderName));

        assertThat(created)
                .as("CONTRIBUTOR holds the write role; refusing it would break the feature")
                .doesNotContain("Not permitted");
        assertThat(callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FOLDER","pageSize":10}""".formatted(folderName)))
                .as("the folder a CONTRIBUTOR created must exist")
                .contains(folderName);
    }
}
