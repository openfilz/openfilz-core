package org.openfilz.dms.e2e.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.e2e.AbstractMcpIT;
import org.openfilz.dms.service.pdf.PdfTestFiles;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The PDF tools as an agent sees them: the seven {@code @Tool} methods over MCP, where
 * {@link PdfToolsIT} drives the same engine over REST. What matters here is the layer the REST
 * suite cannot reach — resolving a PDF or a folder from the name a model made up, the split-mode
 * and page-selection vocabulary, the in-place versus new-document routing, and the messages the
 * tools answer with instead of throwing.
 * <p>
 * Every PDF is generated in-test with a known text per page, and every outcome is verified by
 * downloading the result through the public API.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class PdfAiToolsIT extends AbstractMcpIT {

    private static final String DOCS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;
    private static final Pattern OUTPUT_ID = Pattern.compile("\\(id ([0-9a-f-]{36})");

    PdfAiToolsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void pdfToolProperties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.pdf-tools.active", () -> true);
    }

    @Test
    @DisplayName("getPdfInfo describes pages and bookmarks, and flags a password-protected PDF")
    void describesAPdf() {
        String name = "info-" + suffix() + ".pdf";
        upload(name, PdfTestFiles.pdf(List.of("I1", "I2", "I3"), Map.of(1, "Cover", 3, "Annex")));

        String info = callToolText("getPdfInfo", """
                {"document":"%s"}""".formatted(name));
        assertThat(info).contains("3 pages").contains("bytes").contains("First page")
                .contains("Bookmarks: ").contains("Cover").contains("Annex (p.3)");

        String plain = "plain-" + suffix() + ".pdf";
        upload(plain, PdfTestFiles.pdf("only one"));
        assertThat(callToolText("getPdfInfo", """
                {"document":"%s"}""".formatted(plain))).contains("1 page").contains("No bookmarks");

        String locked = "locked-" + suffix() + ".pdf";
        upload(locked, PdfTestFiles.encryptedPdf("s3cret"));
        assertThat(callToolText("getPdfInfo", """
                {"document":"%s"}""".formatted(locked))).contains("PASSWORD-PROTECTED");
    }

    @Test
    @DisplayName("the tools explain what they could not resolve instead of failing")
    void unresolvableArgumentsAreExplained() {
        String name = "resolve-" + suffix() + ".pdf";
        UUID pdf = upload(name, PdfTestFiles.pdf("R1", "R2"));

        assertThat(callToolText("getPdfInfo", """
                {"document":"no-such-pdf-%s"}""".formatted(suffix()))).contains("No PDF document matching");
        assertThat(callToolText("getPdfInfo", """
                {"document":"  "}""")).contains("A document name (or id) is required");

        // A text file is not a PDF
        String text = "notes-" + suffix() + ".txt";
        upload(text, "just text".getBytes());
        assertThat(callToolText("getPdfInfo", """
                {"document":"%s"}""".formatted(text))).contains("No PDF document matching");

        // Merging needs at least two documents
        assertThat(callToolText("mergePdfs", """
                {"documents":"%s"}""".formatted(name))).contains("at least two PDF documents");

        // An unknown output folder is reported, not created silently
        assertThat(callToolText("extractPdfPages", """
                {"document":"%s","pages":"1","folder":"no-such-folder-%s"}""".formatted(pdf, suffix())))
                .contains("No folder named").contains("createFolder");

        // Missing page selections
        assertThat(callToolText("deletePdfPages", """
                {"document":"%s","pages":"  "}""".formatted(pdf))).contains("pages is required");
        assertThat(callToolText("extractPdfPages", """
                {"document":"%s","pages":""}""".formatted(pdf))).contains("pages is required");
        assertThat(callToolText("reorderPdfPages", """
                {"document":"%s","pageOrder":""}""".formatted(pdf))).contains("pageOrder is required");

        // Unknown split vocabulary
        assertThat(callToolText("splitPdf", """
                {"document":"%s","mode":"in-half"}""".formatted(pdf))).contains("Unknown split mode");
        assertThat(callToolText("splitPdf", """
                {"document":"%s","mode":"at-pages","cutPages":"two"}""".formatted(pdf)))
                .contains("cutPages must be comma-separated page numbers");

        // Deleting every page would leave nothing
        assertThat(callToolText("deletePdfPages", """
                {"document":"%s","pages":"1-2"}""".formatted(pdf))).contains("a PDF needs at least one page");
    }

    @Test
    @DisplayName("merge, extract and split write new documents where the model asked")
    void newDocumentsAreWrittenToTheChosenFolder() {
        String suffix = suffix();
        FolderResponse folder = createFolder("pdf-ai-out-" + suffix, null);
        String first = "part-a-" + suffix + ".pdf";
        String second = "part-b-" + suffix + ".pdf";
        upload(first, PdfTestFiles.pdf("A1", "A2"));
        upload(second, PdfTestFiles.pdf("B1"));

        String merged = callToolText("mergePdfs", """
                {"documents":"%s, %s","outputName":"merged-%s.pdf","folder":"pdf-ai-out-%s","addBookmarks":true}"""
                .formatted(first, second, suffix, suffix));
        assertThat(merged).contains("Merged 2 PDFs").contains("3 pages");
        assertThat(PdfTestFiles.pageTexts(download(outputIdOf(merged)))).containsExactly("A1", "A2", "B1");

        String extracted = callToolText("extractPdfPages", """
                {"document":"%s","pages":"2","outputName":"extract-%s.pdf","folder":"%s"}"""
                .formatted(first, suffix, folder.id()));
        assertThat(extracted).contains("Extracted 1 page");
        assertThat(PdfTestFiles.pageTexts(download(outputIdOf(extracted)))).containsExactly("A2");

        // The original is untouched by an extraction
        assertThat(callToolText("getPdfInfo", """
                {"document":"%s"}""".formatted(first))).contains("2 pages");

        String ranged = "ranges-" + suffix + ".pdf";
        upload(ranged, PdfTestFiles.pdf("R1", "R2", "R3", "R4"));
        String split = callToolText("splitPdf", """
                {"document":"%s","mode":"ranges","ranges":"1-2; 3-","folder":"%s","createSubfolder":true,"namePattern":"{name}-part{index}"}"""
                .formatted(ranged, folder.id()));
        assertThat(split).contains("into 2 documents").contains("part1").contains("part2");
    }

    @Test
    @DisplayName("rotate, delete and reorder update the PDF in place unless a new document is asked for")
    void inPlaceEditsCreateNewVersions() {
        String suffix = suffix();
        String name = "edit-" + suffix + ".pdf";
        UUID pdf = upload(name, PdfTestFiles.pdf("E1", "E2", "E3"));

        // In place: the tool answers with the very document it was given
        String rotated = callToolText("rotatePdf", """
                {"document":"%s","angle":90,"pages":"odd"}""".formatted(name));
        assertThat(rotated).contains("Rotated pages odd");
        assertThat(outputIdOf(rotated)).isEqualTo(pdf);

        String reordered = callToolText("reorderPdfPages", """
                {"document":"%s","pageOrder":"3,1"}""".formatted(name));
        assertThat(reordered).contains("Reordered the pages");
        assertThat(outputIdOf(reordered)).isEqualTo(pdf);
        assertThat(PdfTestFiles.pageTexts(download(pdf))).containsExactly("E3", "E1", "E2");

        String deleted = callToolText("deletePdfPages", """
                {"document":"%s","pages":"2"}""".formatted(name));
        assertThat(deleted).contains("Removed 1 page");
        assertThat(outputIdOf(deleted)).isEqualTo(pdf);
        assertThat(PdfTestFiles.pageTexts(download(pdf))).containsExactly("E3", "E2");

        // saveAs='new-document' writes elsewhere and keeps the original as it is
        String copy = callToolText("deletePdfPages", """
                {"document":"%s","pages":"1","saveAs":"new-document"}""".formatted(name));
        assertThat(copy).contains("Removed 1 page");
        assertThat(outputIdOf(copy)).isNotEqualTo(pdf);
        assertThat(PdfTestFiles.pageTexts(download(outputIdOf(copy)))).containsExactly("E2");
        assertThat(PdfTestFiles.pageTexts(download(pdf))).containsExactly("E3", "E2");

        // Rotating all pages of the copy is accepted too
        assertThat(callToolText("rotatePdf", """
                {"document":"%s","angle":270,"saveAs":"new_document"}""".formatted(name)))
                .contains("Rotated all pages");
    }

    @Test
    @DisplayName("a READER may inspect a PDF but not transform it")
    void readersMayOnlyInspect() {
        String name = "reader-" + suffix() + ".pdf";
        upload(name, PdfTestFiles.pdf("P1", "P2"));

        String admin = accessToken;
        accessToken = getAccessToken("reader-user");
        try {
            assertThat(callToolText("getPdfInfo", """
                    {"document":"%s"}""".formatted(name))).contains("2 pages");
            assertThat(callToolText("rotatePdf", """
                    {"document":"%s","angle":90}""".formatted(name))).contains("Not permitted");
            assertThat(callToolText("splitPdf", """
                    {"document":"%s","mode":"every-page"}""".formatted(name))).contains("Not permitted");
            assertThat(callToolText("mergePdfs", """
                    {"documents":"%s,%s"}""".formatted(name, name))).contains("Not permitted");
        } finally {
            accessToken = admin;
        }

        // and the document is unchanged
        assertThat(callToolText("getPdfInfo", """
                {"document":"%s"}""".formatted(name))).contains("2 pages");
    }

    // ---------------------------------------------------------------- helpers

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID outputIdOf(String answer) {
        Matcher matcher = OUTPUT_ID.matcher(answer);
        assertThat(matcher.find()).as("the answer names the output document; was: %s", answer).isTrue();
        return UUID.fromString(matcher.group(1));
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

    private UUID upload(String name, byte[] bytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
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

    private byte[] download(UUID id) {
        byte[] bytes = getWebTestClient().get().uri(DOCS + "/" + id + "/download")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(bytes).isNotNull();
        return bytes;
    }
}
