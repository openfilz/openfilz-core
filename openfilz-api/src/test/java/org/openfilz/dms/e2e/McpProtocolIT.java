package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.service.mcp.DocumentAiToolsContributor;
import org.openfilz.dms.service.mcp.PdfAiToolsContributor;
import org.openfilz.dms.service.mcp.McpDocumentResources;
import org.openfilz.dms.service.mcp.McpToolCallbackProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Layer 1 of the MCP test strategy: protocol conformance over the real HTTP stack, with a real
 * Keycloak bearer token, against the JVM build (see
 * {@code openfilz-enterprise/docs/private/mcp-server-plan.md}).
 * <p>
 * Two jobs, and the second is why this suite is exhaustive rather than representative:
 * <ol>
 *   <li>Pin the wire contract an external agent sees — handshake, advertised tool surface,
 *       schemas, error shape, and the fact that an unauthenticated call gets nowhere.</li>
 *   <li><b>Drive every tool at least once.</b> Layer 2 runs this suite under GraalVM's tracing
 *       agent to derive the native-image reflection metadata; a tool no test calls is a tool
 *       whose reflective path never gets registered, and it then fails only in an EE native
 *       deployment. {@link #everyAdvertisedToolIsCallable()} exists for that reason, not for its
 *       assertions.</li>
 * </ol>
 * Runs in {@code READ_WRITE} so the full surface is reachable; {@link McpReadOnlyModeIT} pins
 * the default read-only posture.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpProtocolIT extends AbstractMcpIT {

    /** For the size-cap test — the flag is runtime-read, so mutating the bean is effective per call. */
    private final McpProperties mcpProperties;

    public McpProtocolIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder,
                         McpProperties mcpProperties) {
        super(webTestClient, customJacksonJsonEncoder);
        this.mcpProperties = mcpProperties;
    }

    @DynamicPropertySource
    static void useReadWriteMode(DynamicPropertyRegistry registry) {
        // "none": this suite also proves the tool surface stands up with no ChatModel bean at
        // all. McpWithChatModelIT covers the opposite case.
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        // Signed download links, so downloadDocument hands out a clickable expiring URL and the
        // redemption chain can be driven end-to-end (see the download-links test section).
        registry.add("openfilz.download-tokens.enabled", () -> "true");
        registry.add("openfilz.download-tokens.signing-secret", () -> DOWNLOAD_TOKEN_SECRET);
    }

    private static final String DOWNLOAD_TOKEN_SECRET = "mcp-protocol-it-download-secret-0123456789";

    // ---------------------------------------------------------------- handshake

    @Test
    @DisplayName("initialize returns the server identity, protocol version and instructions")
    void initializeHandshake() {
        JsonNode result = expectResult(rpc(1, "initialize", """
                {"protocolVersion":"2025-06-18",
                 "capabilities":{},
                 "clientInfo":{"name":"openfilz-protocol-it","version":"1.0.0"}}"""));

        assertThat(result.path("protocolVersion").asString())
                .as("server must negotiate a protocol version").isNotBlank();
        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("openfilz");
        assertThat(result.path("capabilities").has("tools"))
                .as("a tools-only MCP server must advertise the tools capability").isTrue();
        assertThat(result.path("instructions").asString())
                .as("instructions tell the calling agent how to use the tool surface")
                .contains("queryDocuments");
    }

    // ---------------------------------------------------------------- tools/list

    @Test
    @DisplayName("tools/list advertises the whole DocumentAiTools surface in read-write mode")
    void toolsListAdvertisesEveryTool() {
        Set<String> expected = new HashSet<>(DocumentAiToolsContributor.READ_ONLY_TOOLS);
        expected.addAll(DocumentAiToolsContributor.MUTATING_TOOLS);
        expected.addAll(PdfAiToolsContributor.READ_ONLY_TOOLS);
        expected.addAll(PdfAiToolsContributor.MUTATING_TOOLS);
        expected.addAll(org.openfilz.dms.service.mcp.OrganizeAiToolsContributor.READ_ONLY_TOOLS);
        expected.addAll(org.openfilz.dms.service.mcp.OrganizeAiToolsContributor.MUTATING_TOOLS);
        expected.addAll(org.openfilz.dms.service.mcp.SignatureAiToolsContributor.READ_ONLY_TOOLS);
        expected.addAll(org.openfilz.dms.service.mcp.SignatureAiToolsContributor.MUTATING_TOOLS);

        assertThat(advertisedToolNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("every advertised tool carries a description and a JSON input schema")
    void everyToolIsSelfDescribing() {
        for (JsonNode tool : listTools()) {
            String name = tool.get("name").asString();
            assertThat(tool.path("description").asString())
                    .as("%s must describe itself — the description is what an agent selects on", name)
                    .isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asString())
                    .as("%s must expose a JSON Schema for its arguments", name)
                    .isEqualTo("object");
        }
    }

    @Test
    @DisplayName("queryDocuments exposes its documented parameters in the input schema")
    void queryDocumentsSchemaIsComplete() {
        JsonNode properties = listTools().stream()
                .filter(tool -> "queryDocuments".equals(tool.get("name").asString()))
                .findFirst()
                .orElseThrow()
                .path("inputSchema").path("properties");

        assertThat(properties.propertyNames())
                .contains("folder", "nameLike", "type", "sortBy", "sortOrder", "pageSize", "countOnly");
    }

    // ---------------------------------------------------------------- security

    @Test
    @DisplayName("an unauthenticated MCP request is rejected before reaching any tool")
    void unauthenticatedRequestIsRejected() {
        webTestClient.post().uri(MCP_ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rpcBody(99, "tools/list", null))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("a bearer token that is not a valid JWT is rejected")
    void garbageTokenIsRejected() {
        webTestClient.post().uri(MCP_ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .bodyValue(rpcBody(98, "tools/list", null))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------------------------------------------------------------- tools/call

    @Test
    @DisplayName("createFolder then queryDocuments round-trips through the MCP tool layer")
    void toolCallCreatesAndFindsAFolder() {
        String folderName = "mcp-it-" + UUID.randomUUID().toString().substring(0, 8);

        String created = callToolText("createFolder", """
                {"name":"%s"}""".formatted(folderName));
        assertThat(created)
                .as("createFolder should report what it created")
                .containsIgnoringCase(folderName);

        String found = callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FOLDER","pageSize":10}""".formatted(folderName));
        assertThat(found)
                .as("a folder created through MCP must be findable through MCP")
                .contains(folderName);
    }

    @Test
    @DisplayName("describeImage degrades explicitly when no chat model is configured")
    void visionToolDegradesWithoutAChatModel() {
        // The context deliberately has no ChatModel bean (selectors pinned to "none"): an MCP
        // deployment need not run an LLM of its own. The tool must say so rather than NPE.
        String answer = callToolText("describeImage", """
                {"imageName":"whatever.png","task":"describe"}""");

        assertThat(answer).containsIgnoringCase("unavailable");
    }

    @Test
    @DisplayName("an unknown tool name fails cleanly instead of surfacing a stack trace")
    void unknownToolIsRejectedCleanly() {
        JsonNode response = rpc(50, "tools/call", """
                {"name":"deleteEverything","arguments":{}}""");

        boolean rpcError = response.has("error");
        boolean toolError = response.path("result").path("isError").asBoolean(false);
        assertThat(rpcError || toolError)
                .as("calling a tool that does not exist must fail; response was: %s", response)
                .isTrue();
    }

    // ---------------------------------------------------------------- resources
    // downloadDocument is served natively (McpDocumentResources): its result carries a
    // resource_link content block next to the text fallback, and resources/read serves the
    // original bytes over the same authenticated connection. These tests also drive the
    // resource read path for layer 2's native-image trace.

    @Test
    @DisplayName("initialize advertises the resources capability alongside tools")
    void initializeAdvertisesResources() {
        JsonNode result = expectResult(rpc(70, "initialize", """
                {"protocolVersion":"2025-06-18",
                 "capabilities":{},
                 "clientInfo":{"name":"openfilz-protocol-it","version":"1.0.0"}}"""));

        assertThat(result.path("capabilities").has("resources"))
                .as("a server exposing openfilz://documents/{id} must advertise resources")
                .isTrue();
    }

    @Test
    @DisplayName("resources/templates/list advertises the document template and nothing enumerable")
    void resourceTemplateIsAdvertised() {
        JsonNode templatesResult = expectResult(rpc(71, "resources/templates/list", "{}"));
        List<String> templates = new ArrayList<>();
        templatesResult.path("resourceTemplates")
                .forEach(template -> templates.add(template.path("uriTemplate").asString()));
        assertThat(templates).contains(McpDocumentResources.DOCUMENT_URI_TEMPLATE);

        // resources/list stays empty by design: the list is per-deployment, not per-caller,
        // so enumerating documents there would be a leak. Per-call authorization happens in
        // the read handler instead.
        JsonNode listResult = expectResult(rpc(72, "resources/list", "{}"));
        assertThat(listResult.path("resources").isEmpty())
                .as("no document may ever be enumerated in the static resources/list")
                .isTrue();
    }

    @Test
    @DisplayName("downloadDocument returns a resource_link whose resources/read serves the original bytes")
    void downloadDocumentServesResourceLinkAndTextFallback() {
        String probe = "mcp-res-" + UUID.randomUUID().toString().substring(0, 8);
        String fileContent = "resource content " + probe;
        callToolText("writeFile", """
                {"fileName":"%s.txt","content":"%s"}""".formatted(probe, fileContent));

        JsonNode result = expectResult(rpc(73, "tools/call", """
                {"name":"downloadDocument","arguments":{"documentName":"%s.txt"}}""".formatted(probe)));
        assertThat(result.path("isError").asBoolean(false)).isFalse();

        String linkUri = null;
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            switch (block.path("type").asString()) {
                case "resource_link" -> linkUri = block.path("uri").asString();
                case "text" -> text.append(block.path("text").asString());
            }
        }
        assertThat(linkUri)
                .as("a resource_link block, for clients that can follow it")
                .startsWith(McpDocumentResources.DOCUMENT_URI_PREFIX);
        assertThat(text.toString())
                .as("the text fallback still carries the extracted content for tools-only clients")
                .contains(fileContent);

        JsonNode read = expectResult(readResource(linkUri));
        JsonNode contents = read.path("contents").path(0);
        assertThat(contents.path("uri").asString()).isEqualTo(linkUri);
        byte[] blob = Base64.getDecoder().decode(contents.path("blob").asString());
        assertThat(new String(blob, StandardCharsets.UTF_8))
                .as("resources/read must serve the original bytes, not extracted text")
                .isEqualTo(fileContent);
    }

    @Test
    @DisplayName("resources/read refuses an unknown id and a malformed uri without an existence oracle")
    void unknownResourceIsNotFound() {
        JsonNode unknownId = readResource(McpDocumentResources.DOCUMENT_URI_PREFIX + UUID.randomUUID());
        assertThat(unknownId.has("error"))
                .as("an id nobody can see must be an error, not empty contents: %s", unknownId)
                .isTrue();
        // "not visible to you" wording only — never a permission-vs-existence distinction
        assertThat(unknownId.path("error").path("message").asString().toLowerCase())
                .doesNotContain("permission", "forbidden");

        JsonNode malformed = readResource("openfilz://documents/not-a-uuid");
        assertThat(malformed.has("error"))
                .as("a uri outside the strict template must be refused: %s", malformed)
                .isTrue();
    }

    @Test
    @DisplayName("a document above the configured resource size cap is refused with guidance")
    void oversizedResourceIsRefused() {
        String probe = "mcp-cap-" + UUID.randomUUID().toString().substring(0, 8);
        callToolText("writeFile", """
                {"fileName":"%s.txt","content":"more than one byte"}""".formatted(probe));
        JsonNode result = expectResult(rpc(74, "tools/call", """
                {"name":"downloadDocument","arguments":{"documentName":"%s.txt"}}""".formatted(probe)));
        String linkUri = null;
        for (JsonNode block : result.path("content")) {
            if ("resource_link".equals(block.path("type").asString())) {
                linkUri = block.path("uri").asString();
            }
        }
        assertThat(linkUri).isNotNull();

        long originalCap = mcpProperties.getMaxResourceSizeBytes();
        try {
            mcpProperties.setMaxResourceSizeBytes(1);
            JsonNode refused = readResource(linkUri);
            assertThat(refused.has("error"))
                    .as("an oversized blob must be refused, not truncated: %s", refused)
                    .isTrue();
            assertThat(refused.path("error").path("message").asString())
                    .as("the refusal points the caller at the browser download instead")
                    .contains("limit");
        } finally {
            mcpProperties.setMaxResourceSizeBytes(originalCap);
        }
    }

    @Test
    @DisplayName("an unauthenticated resources/read is rejected before reaching any handler")
    void unauthenticatedResourceReadIsRejected() {
        webTestClient.post().uri(MCP_ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rpcBody(97, "resources/read", """
                        {"uri":"%s%s"}""".formatted(McpDocumentResources.DOCUMENT_URI_PREFIX, UUID.randomUUID())))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------------------------------------------------------------- signed download links
    // With openfilz.download-tokens enabled, downloadDocument's URL carries a short-lived
    // HS256 token minted for the calling user and bound to that one document — clickable from
    // the conversation with no bearer header. DownloadTokenSecurityConfig redeems it; any
    // failure answers a uniform 404.

    @Test
    @DisplayName("the signed download link works unauthenticated, for exactly its document")
    void signedDownloadLinkServesTheDocument() {
        String probe = "mcp-dl-" + UUID.randomUUID().toString().substring(0, 8);
        String fileContent = "signed link content " + probe;
        callToolText("writeFile", """
                {"fileName":"%s.txt","content":"%s"}""".formatted(probe, fileContent));

        String answer = callToolText("downloadDocument", """
                {"documentName":"%s.txt"}""".formatted(probe));
        assertThat(answer)
                .as("the tool must describe the link as expiring and header-free")
                .contains("no sign-in needed");
        String tokenizedPath = tokenizedDownloadPath(answer);

        // The whole point: no Authorization header, still exactly this one document
        webTestClient.get().uri(tokenizedPath)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(fileContent);

        // Tampered token → uniform 404
        webTestClient.get().uri(tokenizedPath + "x")
                .exchange()
                .expectStatus().isNotFound();

        // The same valid token on a different document's path → uniform 404 (one token, one file)
        String otherProbe = "mcp-dl2-" + UUID.randomUUID().toString().substring(0, 8);
        callToolText("writeFile", """
                {"fileName":"%s.txt","content":"other"}""".formatted(otherProbe));
        String otherPath = tokenizedDownloadPath(callToolText("downloadDocument", """
                {"documentName":"%s.txt"}""".formatted(otherProbe)));
        String swapped = otherPath.substring(0, otherPath.indexOf("?token="))
                + tokenizedPath.substring(tokenizedPath.indexOf("?token="));
        webTestClient.get().uri(swapped)
                .exchange()
                .expectStatus().isNotFound();

        // And the plain (token-less) endpoint still demands a bearer token — the chain narrowed nothing
        webTestClient.get().uri(tokenizedPath.substring(0, tokenizedPath.indexOf("?token=")))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Extract the tokenized download URL from a tool answer, as a server-relative path+query. */
    private static String tokenizedDownloadPath(String toolAnswer) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("https?://\\S+?(/api/v1/documents/[0-9a-f-]{36}/download\\?token=\\S+)")
                .matcher(toolAnswer);
        assertThat(matcher.find())
                .as("downloadDocument must hand out a tokenized download URL; answer was: %s", toolAnswer)
                .isTrue();
        return matcher.group(1);
    }

    /**
     * Calls every tool the server advertises. Assertions are deliberately weak — the arguments
     * are benign and several tools legitimately answer "not found". What matters is that each
     * tool completes a full dispatch, so layer 2's tracing agent observes every reflective path.
     */
    @Test
    @DisplayName("every advertised tool completes a dispatch (native-hint trace driver)")
    void everyAdvertisedToolIsCallable() {
        String probe = "mcp-probe-" + UUID.randomUUID().toString().substring(0, 8);

        for (String tool : advertisedToolNames()) {
            String arguments = argumentsFor(tool, probe);

            JsonNode response = rpc(100, "tools/call", """
                    {"name":"%s","arguments":%s}""".formatted(tool, arguments));

            assertThat(response.has("error"))
                    .as("tool '%s' must not fail at the protocol level; response was: %s", tool, response)
                    .isFalse();
            assertThat(response.path("result").path("content").isArray())
                    .as("tool '%s' must return MCP content blocks", tool)
                    .isTrue();
            // Not merely "no exception": argument-schema validation fails BEFORE the tool body
            // runs, so an isError result would trace no reflective path at all and layer 2 would
            // silently under-generate the native metadata.
            assertThat(response.path("result").path("isError").asBoolean(false))
                    .as("tool '%s' must actually execute, not fail validation; response was: %s", tool, response)
                    .isFalse();
        }
    }

    private static String argumentsFor(String tool, String probe) {
        return switch (tool) {
            case "whoami" -> "{}";
            case "queryDocuments" -> """
                    {"sortBy":"updatedAt","sortOrder":"DESC","pageSize":5,"countOnly":false}""";
            case "readDocumentContent" -> """
                    {"documentName":"%s"}""".formatted(probe);
            case "getDocumentActivity" -> """
                    {"document":"%s","limit":5}""".formatted(probe);
            case "getDocumentPath" -> """
                    {"documentId":"%s"}""".formatted(UUID.randomUUID());
            case "describeImage" -> """
                    {"imageName":"%s","task":"describe"}""".formatted(probe);
            case "createFolder" -> """
                    {"name":"%s"}""".formatted(probe);
            case "writeFile" -> """
                    {"fileName":"%s.txt","content":"hello from MCP"}""".formatted(probe);
            case "moveDocuments" -> """
                    {"documentNames":"%s.txt","targetFolder":"%s"}""".formatted(probe, probe);
            case "renameDocument" -> """
                    {"documentName":"%s.txt","newName":"%s-renamed.txt"}""".formatted(probe, probe);
            case "getMetadata" -> """
                    {"documentName":"%s"}""".formatted(probe);
            case "searchByMetadata" -> """
                    {"metadataJson":"{\\"absent-%s\\":\\"x\\"}"}""".formatted(probe);
            case "updateMetadata" -> """
                    {"documentName":"%s","metadataJson":"{\\"k\\":\\"v\\"}"}""".formatted(probe);
            case "deleteMetadata" -> """
                    {"documentName":"%s","keys":"k"}""".formatted(probe);
            case "deleteDocument" -> """
                    {"documentName":"%s"}""".formatted(probe);
            case "listVersions" -> """
                    {"documentName":"%s"}""".formatted(probe);
            case "restoreVersion" -> """
                    {"documentName":"%s","versionId":"v1"}""".formatted(probe);
            case "downloadDocument" -> """
                    {"documentName":"%s"}""".formatted(probe);
            // PDF tools (PdfAiTools): unknown names resolve to a text result, which is what the trace needs
            case "getPdfInfo" -> """
                    {"document":"%s.pdf"}""".formatted(probe);
            case "mergePdfs" -> """
                    {"documents":"%s-a.pdf,%s-b.pdf"}""".formatted(probe, probe);
            case "splitPdf" -> """
                    {"document":"%s.pdf","mode":"every-page"}""".formatted(probe);
            case "rotatePdf" -> """
                    {"document":"%s.pdf","angle":90}""".formatted(probe);
            case "deletePdfPages" -> """
                    {"document":"%s.pdf","pages":"1"}""".formatted(probe);
            case "extractPdfPages" -> """
                    {"document":"%s.pdf","pages":"1"}""".formatted(probe);
            case "reorderPdfPages" -> """
                    {"document":"%s.pdf","pageOrder":"2,1"}""".formatted(probe);
            case "createBlankDocument" -> """
                    {"name":"%s-blank","documentType":"TEXT"}""".formatted(probe);
            // Reorganisation tools (OrganizeAiTools): an unknown document / plan resolves to a text result
            case "planReorganization" -> """
                    {"maxDepth":2,"maxItems":20}""";
            case "proposeReorganizationPlan" -> """
                    {"planJson":"{\\"moves\\":[{\\"document\\":\\"%s\\",\\"target\\":\\"Sorted\\"}]}"}""".formatted(probe);
            case "applyReorganizationPlan" -> """
                    {"planId":"%s"}""".formatted(UUID.randomUUID());
            case "getReorganizationPlan" -> """
                    {"planId":"%s"}""".formatted(UUID.randomUUID());
            // e-Sign tools (SignatureAiTools): disabled or not-found both answer with text
            case "listSignatureTemplates" -> "{}";
            case "listSignatureEnvelopes" -> """
                    {"status":"SENT"}""";
            case "getSignatureStatus" -> """
                    {"envelope":"%s"}""".formatted(probe);
            case "sendForSignature" -> """
                    {"document":"%s.pdf","recipients":"Probe Signer <%s@example.com>"}""".formatted(probe, probe);
            default -> throw new AssertionError("""
                    Unknown MCP tool '%s'. A tool was added to DocumentAiTools without being \
                    classified here — add arguments for it (and list it in \
                    DocumentAiToolsContributor.MUTATING_TOOLS if it changes anything), otherwise \
                    layer 2 never traces it and it fails in the EE native image."""
                    .formatted(tool));
        };
    }
}
