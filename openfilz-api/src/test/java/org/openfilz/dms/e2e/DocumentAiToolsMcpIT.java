package org.openfilz.dms.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The document tools an external agent actually drives, over MCP, against real storage and a real
 * database: what each tool answers when the model names something that does not exist, names a
 * folder where a file is expected, or leaves a required argument empty — and what it answers when
 * everything is right.
 * <p>
 * {@code DocumentAiToolsIT} pins the same tools against mocks; this suite is about the behaviour
 * the mocks cannot show — resolution by partial name, the folder-scoped search, metadata and
 * version round-trips, and the download link.
 * <p>
 * Shares its Spring context with {@link OrganizeAiToolsIT} and {@link ReorganizationEdgeCasesIT}
 * (same properties, same imported test configuration).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
class DocumentAiToolsMcpIT extends AbstractMcpIT {

    private static final String DOCS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    DocumentAiToolsMcpIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void documentToolProperties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.ai.active", () -> true);
    }

    @Test
    @DisplayName("queryDocuments scopes, filters, sorts and counts, and names the folder it cannot find")
    void queryDocumentsScopesAndCounts() {
        String suffix = suffix();
        FolderResponse folder = createFolder("query-" + suffix, null);
        upload("alpha-" + suffix + ".txt", "alpha".getBytes(), folder.id());
        upload("beta-" + suffix + ".txt", "beta".getBytes(), folder.id());
        createFolder("nested-" + suffix, folder.id());

        String inFolder = callToolText("queryDocuments", """
                {"folder":"query-%s","type":"FILE","sortBy":"name","sortOrder":"ASC","pageSize":5}""".formatted(suffix));
        assertThat(inFolder).contains("alpha-" + suffix).contains("beta-" + suffix)
                .contains("[FILE]").doesNotContain("nested-" + suffix);

        assertThat(callToolText("queryDocuments", """
                {"folder":"query-%s","type":"FOLDER"}""".formatted(suffix)))
                .contains("nested-" + suffix).doesNotContain("alpha-" + suffix);

        // An unrecognised type is ignored rather than refused
        assertThat(callToolText("queryDocuments", """
                {"folder":"query-%s","type":"BANANA"}""".formatted(suffix))).contains("alpha-" + suffix);

        // Across every folder, by partial name
        assertThat(callToolText("queryDocuments", """
                {"folder":"all","nameLike":"alpha-%s"}""".formatted(suffix))).contains("alpha-" + suffix);

        // Counting instead of listing
        assertThat(callToolText("queryDocuments", """
                {"folder":"query-%s","countOnly":true}""".formatted(suffix))).contains("Found 3 document(s)");

        // The root level holds the folder itself, not its contents
        assertThat(callToolText("queryDocuments", """
                {"folder":"root","nameLike":"query-%s"}""".formatted(suffix))).contains("query-" + suffix);

        assertThat(callToolText("queryDocuments", """
                {"nameLike":"nothing-matches-%s"}""".formatted(UUID.randomUUID()))).contains("No documents found");

        assertThat(callToolText("queryDocuments", """
                {"folder":"no-such-folder-%s"}""".formatted(suffix))).contains("No folder named");
    }

    @Test
    @DisplayName("readDocumentContent searches inside a named folder and says what it found instead")
    void readDocumentContentResolvesWithinAFolder() {
        String suffix = suffix();
        FolderResponse folder = createFolder("read-" + suffix, null);
        upload("notes-" + suffix + ".txt", "the quick brown fox".getBytes(), folder.id());

        assertThat(callToolText("readDocumentContent", """
                {"documentName":"notes-%s.txt","folderName":"read-%s"}""".formatted(suffix, suffix)))
                .contains("the quick brown fox");

        // Found without the folder hint too
        assertThat(callToolText("readDocumentContent", """
                {"documentName":"notes-%s.txt"}""".formatted(suffix))).contains("the quick brown fox");

        // In the folder, but under another name: the tool lists what the folder holds
        assertThat(callToolText("readDocumentContent", """
                {"documentName":"absent-%s.txt","folderName":"read-%s"}""".formatted(suffix, suffix)))
                .contains("No file matching").contains("notes-" + suffix + ".txt");

        // An unknown folder hint falls back to the global search, which finds nothing here
        assertThat(callToolText("readDocumentContent", """
                {"documentName":"absent-%s.txt","folderName":"no-such-folder-%s"}""".formatted(suffix, suffix)))
                .contains("not found");

        // A folder is not a document
        assertThat(callToolText("readDocumentContent", """
                {"documentName":"read-%s"}""".formatted(suffix))).contains("is a folder");
    }

    @Test
    @DisplayName("createBlankDocument makes each office type and refuses what it cannot make")
    void createBlankDocumentsOfEveryType() {
        String suffix = suffix();
        FolderResponse folder = createFolder("blank-" + suffix, null);

        for (String type : new String[]{"WORD", "EXCEL", "POWERPOINT", "TEXT"}) {
            String created = callToolText("createBlankDocument", """
                    {"name":"%s-%s","documentType":"%s","folderName":"blank-%s"}"""
                    .formatted(type.toLowerCase(), suffix, type, suffix));
            assertThat(created).as("creating a blank %s", type).contains("created successfully");
        }
        assertThat(callToolText("queryDocuments", """
                {"folder":"blank-%s"}""".formatted(suffix)))
                .contains("word-" + suffix).contains("excel-" + suffix)
                .contains("powerpoint-" + suffix).contains("text-" + suffix);

        assertThat(callToolText("createBlankDocument", """
                {"name":"nope-%s","documentType":"PARCHMENT"}""".formatted(suffix)))
                .contains("Unknown document type");
        assertThat(callToolText("createBlankDocument", """
                {"name":"  ","documentType":"WORD"}""")).contains("A document name is required");
        assertThat(callToolText("createBlankDocument", """
                {"name":"orphan-%s","documentType":"WORD","folderName":"no-such-folder-%s"}"""
                .formatted(suffix, suffix))).contains("No folder named");
    }

    @Test
    @DisplayName("moveDocuments needs an existing target and reports items it cannot resolve")
    void moveDocumentsResolvesBothEnds() {
        String suffix = suffix();
        FolderResponse source = createFolder("move-src-" + suffix, null);
        FolderResponse target = createFolder("move-dst-" + suffix, null);
        UUID file = upload("movable-" + suffix + ".txt", "move me".getBytes(), source.id());

        assertThat(callToolText("moveDocuments", """
                {"documentNames":"movable-%s.txt","targetFolder":"no-such-folder-%s"}""".formatted(suffix, suffix)))
                .contains("No folder named").contains("createFolder");

        assertThat(callToolText("moveDocuments", """
                {"documentNames":"movable-%s.txt","targetFolder":"move-dst-%s"}""".formatted(suffix, suffix)))
                .contains("moved 1");
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(file))).contains("move-dst-" + suffix);

        // By id, back to the root level
        assertThat(callToolText("moveDocuments", """
                {"documentNames":"%s","targetFolder":"root"}""".formatted(file))).doesNotContain("No folder named");

        // A name nobody has ever seen
        assertThat(callToolText("moveDocuments", """
                {"documentNames":"ghost-%s.txt","targetFolder":"move-dst-%s"}""".formatted(suffix, suffix)))
                .doesNotContain("moved 1 item");
    }

    @Test
    @DisplayName("metadata is written, read back, searched and removed through the tools")
    void metadataRoundTrip() {
        String suffix = suffix();
        FolderResponse folder = createFolder("meta-" + suffix, null);
        upload("tagged-" + suffix + ".txt", "content".getBytes(), folder.id());

        assertThat(callToolText("getMetadata", """
                {"documentName":"tagged-%s.txt"}""".formatted(suffix))).contains("has no metadata");

        assertThat(callToolText("updateMetadata", """
                {"documentName":"tagged-%s.txt","metadataJson":"{\\"status\\":\\"reviewed\\",\\"year\\":2026}"}"""
                .formatted(suffix))).contains("Updated 2 metadata key(s)");
        assertThat(callToolText("getMetadata", """
                {"documentName":"tagged-%s.txt"}""".formatted(suffix))).contains("reviewed").contains("2026");

        assertThat(callToolText("searchByMetadata", """
                {"metadataJson":"{\\"status\\":\\"reviewed\\"}","type":"FILE","folderName":"meta-%s"}""".formatted(suffix)))
                .contains("tagged-" + suffix + ".txt");
        assertThat(callToolText("searchByMetadata", """
                {"metadataJson":"{\\"status\\":\\"never-set-%s\\"}"}""".formatted(suffix)))
                .contains("No documents match");
        assertThat(callToolText("searchByMetadata", """
                {"metadataJson":"not json"}""")).contains("Provide the metadata to match");

        assertThat(callToolText("deleteMetadata", """
                {"documentName":"tagged-%s.txt","keys":"status"}""".formatted(suffix)))
                .contains("Removed 1 metadata key(s)");
        assertThat(callToolText("deleteMetadata", """
                {"documentName":"tagged-%s.txt","keys":" , "}""".formatted(suffix)))
                .contains("Provide at least one metadata key");
        assertThat(callToolText("updateMetadata", """
                {"documentName":"tagged-%s.txt","metadataJson":"{}"}""".formatted(suffix)))
                .contains("Provide the metadata to set");

        // Nothing of this works on a document the model invented
        String ghost = "ghost-" + suffix + ".txt";
        assertThat(callToolText("getMetadata", """
                {"documentName":"%s"}""".formatted(ghost))).contains("is visible to you");
        assertThat(callToolText("updateMetadata", """
                {"documentName":"%s","metadataJson":"{\\"a\\":1}"}""".formatted(ghost))).contains("You cannot modify");
        assertThat(callToolText("deleteMetadata", """
                {"documentName":"%s","keys":"a"}""".formatted(ghost))).contains("You cannot modify");
    }

    @Test
    @DisplayName("downloadDocument returns text and a link, and refuses folders and unknown names")
    void downloadDocumentServesTextAndLinks() {
        String suffix = suffix();
        FolderResponse folder = createFolder("dl-" + suffix, null);
        upload("readable-" + suffix + ".txt", "downloadable text".getBytes(), folder.id());

        String text = callToolText("downloadDocument", """
                {"documentName":"readable-%s.txt"}""".formatted(suffix));
        assertThat(text).contains("downloadable text").contains("/download");

        assertThat(callToolText("downloadDocument", """
                {"documentName":"dl-%s"}""".formatted(suffix))).contains("not a downloadable file");
        assertThat(callToolText("downloadDocument", """
                {"documentName":"ghost-%s"}""".formatted(suffix))).contains("is visible to you");
    }

    @Test
    @DisplayName("versions are listed and restore refuses what it cannot restore")
    void versionToolsReportTheirLimits() {
        String suffix = suffix();
        upload("versioned-" + suffix + ".txt", "v1".getBytes(), null);

        // Local storage keeps no object versions, so the tool says so rather than inventing one
        assertThat(callToolText("listVersions", """
                {"documentName":"versioned-%s.txt"}""".formatted(suffix)))
                .containsAnyOf("no stored versions", "versioning is not enabled");
        assertThat(callToolText("listVersions", """
                {"documentName":"ghost-%s.txt"}""".formatted(suffix))).contains("is visible to you");

        assertThat(callToolText("restoreVersion", """
                {"documentName":"versioned-%s.txt","versionId":"  "}""".formatted(suffix)))
                .contains("Provide the versionId");
        assertThat(callToolText("restoreVersion", """
                {"documentName":"ghost-%s.txt","versionId":"whatever"}""".formatted(suffix)))
                .contains("You cannot modify");
    }

    @Test
    @DisplayName("deleteDocument removes a file and a folder, and refuses a name it cannot resolve")
    void deleteDocumentRemovesFilesAndFolders() {
        String suffix = suffix();
        FolderResponse folder = createFolder("del-" + suffix, null);
        upload("doomed-" + suffix + ".txt", "bye".getBytes(), folder.id());

        assertThat(callToolText("deleteDocument", """
                {"documentName":"doomed-%s.txt"}""".formatted(suffix))).contains("Deleted");
        assertThat(callToolText("deleteDocument", """
                {"documentName":"del-%s"}""".formatted(suffix))).contains("Deleted folder");
        assertThat(callToolText("deleteDocument", """
                {"documentName":"ghost-%s"}""".formatted(suffix))).contains("You cannot delete");
    }

    @Test
    @DisplayName("createFolder, writeFile, renameDocument and getDocumentPath guard their inputs")
    void writeRenameAndPathGuards() {
        String suffix = suffix();
        String folderName = "write-" + suffix;
        assertThat(callToolText("createFolder", """
                {"name":"%s"}""".formatted(folderName))).contains("created successfully");
        // A parent the model invented is refused rather than silently becoming the root
        assertThat(callToolText("createFolder", """
                {"name":"child-%s","parentFolderId":"no-such-parent-%s"}""".formatted(suffix, suffix)))
                .contains("No parent folder");
        assertThat(callToolText("createFolder", """
                {"name":"child-%s","parentFolderId":"%s"}""".formatted(suffix, folderName)))
                .contains("created successfully");

        assertThat(callToolText("writeFile", """
                {"fileName":"written-%s.txt","content":"hello from the agent","folderName":"%s"}"""
                .formatted(suffix, folderName))).contains("written-" + suffix);
        assertThat(callToolText("readDocumentContent", """
                {"documentName":"written-%s.txt"}""".formatted(suffix))).contains("hello from the agent");
        assertThat(callToolText("writeFile", """
                {"fileName":"orphan-%s.txt","content":"x","folderName":"no-such-folder-%s"}"""
                .formatted(suffix, suffix))).contains("No folder named");

        assertThat(callToolText("renameDocument", """
                {"documentName":"written-%s.txt","newName":"renamed-%s.txt"}""".formatted(suffix, suffix)))
                .contains("renamed-" + suffix);
        assertThat(callToolText("renameDocument", """
                {"documentName":"ghost-%s.txt","newName":"whatever-%s.txt"}""".formatted(suffix, suffix)))
                .doesNotContain("renamed to");

        assertThat(callToolText("getDocumentPath", """
                {"documentId":"not-a-uuid"}""")).doesNotContain("Error:");
        assertThat(callToolText("getDocumentPath", """
                {"documentId":"%s"}""".formatted(UUID.randomUUID()))).doesNotContain("Error:");
    }

    // ---------------------------------------------------------------- helpers

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private FolderResponse createFolder(String name, UUID parentId) {
        FolderResponse folder = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFolderRequest(name, parentId))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
        assertThat(folder).isNotNull();
        return folder;
    }

    private UUID upload(String name, byte[] bytes, UUID parentId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        if (parentId != null) {
            builder.part("parentFolderId", parentId.toString());
        }
        UploadResponse response = getWebTestClient().post()
                .uri(u -> u.path(DOCS + "/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        return response.id();
    }
}
