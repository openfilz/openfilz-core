package org.openfilz.dms.e2e.pdf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.pdf.MergeRequest;
import org.openfilz.dms.dto.request.pdf.MergeSource;
import org.openfilz.dms.dto.request.pdf.OrganizeRequest;
import org.openfilz.dms.dto.request.pdf.OutputMode;
import org.openfilz.dms.dto.request.pdf.OutputTarget;
import org.openfilz.dms.dto.request.pdf.PageInstruction;
import org.openfilz.dms.dto.request.pdf.RotateRequest;
import org.openfilz.dms.dto.request.pdf.SplitMode;
import org.openfilz.dms.dto.request.pdf.SplitOutput;
import org.openfilz.dms.dto.request.pdf.SplitRequest;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.response.pdf.PdfInfo;
import org.openfilz.dms.dto.response.pdf.PdfOperationResponse;
import org.openfilz.dms.dto.response.pdf.PdfOutlineEntry;
import org.openfilz.dms.dto.response.pdf.PdfOutputInfo;
import org.openfilz.dms.e2e.TestContainersKeyCloakConfig;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The PDF tools over REST with real Keycloak JWTs: every flow (info, merge, split, organize, rotate),
 * the write-back routing (new document vs. new version), the provenance audit entry, the role gate
 * (READER may inspect but not transform) and the input guards. All state is created and asserted
 * through the API; PDFs are generated in-test with a known text per page.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class PdfToolsIT extends TestContainersKeyCloakConfig {

    private static final String PDF = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_PDF;
    private static final String DOCS = RestApiClientPaths.DOCS;

    private static String contributor;
    private static String reader;
    private static String admin;

    PdfToolsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void pdfToolsProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.pdf-tools.active", () -> true);
        registry.add("openfilz.pdf-tools.max-outputs", () -> 4);
    }

    @BeforeAll
    static void tokens() {
        contributor = getAccessToken("contributor-user");
        reader = getAccessToken("reader-user");
        admin = getAccessToken("admin-user");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID upload(String token, String name, byte[] bytes) {
        return upload(token, name, bytes, null);
    }

    private UUID upload(String token, String name, byte[] bytes, UUID parentId) {
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
        UploadResponse resp = getWebTestClient().post()
                .uri(u -> u.path(DOCS + "/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        assertThat(resp.id()).isNotNull();
        return resp.id();
    }

    private byte[] download(String token, UUID id) {
        return getWebTestClient().get().uri(DOCS + "/" + id + "/download")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
    }

    private DocumentInfo documentInfo(String token, UUID id) {
        return getWebTestClient().get().uri(DOCS + "/" + id + "/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(DocumentInfo.class).returnResult().getResponseBody();
    }

    private PdfInfo info(String token, UUID id) {
        return getWebTestClient().get().uri(PDF + "/" + id + "/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(PdfInfo.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec post(String token, String path, Object body) {
        return getWebTestClient().post().uri(PDF + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private PdfOperationResponse ok(WebTestClient.ResponseSpec spec) {
        PdfOperationResponse r = spec.expectStatus().isOk()
                .expectBody(PdfOperationResponse.class).returnResult().getResponseBody();
        assertThat(r).isNotNull();
        return r;
    }

    private String auditTrail(UUID id) {
        return getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/audit/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static OutputTarget newDocument(String name) {
        return new OutputTarget(OutputMode.NEW_DOCUMENT, null, name, true, null);
    }

    // ── info ────────────────────────────────────────────────────────────────

    @Test
    void infoDescribesPagesAndBookmarks_andReaderMayInspect() {
        UUID id = upload(contributor, "book.pdf", PdfTestFiles.pdf(List.of("B1", "B2", "B3"), Map.of(1, "Start", 3, "End")));
        PdfInfo info = info(reader, id);
        assertThat(info.documentId()).isEqualTo(id);
        assertThat(info.name()).isEqualTo("book.pdf");
        assertThat(info.pageCount()).isEqualTo(3);
        assertThat(info.pages()).hasSize(3);
        assertThat(info.pages().getFirst().width()).isGreaterThan(500);
        assertThat(info.encrypted()).isFalse();
        assertThat(info.signed()).isFalse();
        assertThat(info.outline()).extracting(PdfOutlineEntry::title).containsExactly("Start", "End");
        assertThat(info.outline()).extracting(PdfOutlineEntry::page).containsExactly(1, 3);
        assertThat(info.size()).isGreaterThan(0);
    }

    @Test
    void settingsExposeTheToggle() {
        Settings settings = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.pdfToolsActive()).isTrue();
    }

    // ── merge ───────────────────────────────────────────────────────────────

    @Test
    void mergeCreatesANewDocumentWithProvenance() {
        UUID a = upload(contributor, "alpha.pdf", PdfTestFiles.pdf("A1", "A2", "A3"));
        UUID b = upload(contributor, "beta.pdf", PdfTestFiles.pdf("B1", "B2"));

        PdfOperationResponse r = ok(post(contributor, "/merge",
                new MergeRequest(List.of(new MergeSource(a, null), new MergeSource(b, null)), true, null)));
        assertThat(r.operation()).isEqualTo("merge");
        PdfOutputInfo out = r.outputs().getFirst();
        assertThat(out.name()).isEqualTo("alpha (merged).pdf");
        assertThat(out.pageCount()).isEqualTo(5);
        assertThat(out.documentId()).isNotIn(a, b);

        byte[] merged = download(contributor, out.documentId());
        assertThat(PdfTestFiles.pageTexts(merged)).containsExactly("A1", "A2", "A3", "B1", "B2");
        assertThat(PdfTestFiles.bookmarkTitles(merged)).containsExactly("alpha", "beta");
        assertThat(documentInfo(contributor, out.documentId()).contentType()).isEqualTo("application/pdf");

        String trail = auditTrail(out.documentId());
        assertThat(trail).contains("PDF_TRANSFORM").contains("\"operation\":\"merge\"").contains(a.toString()).contains(b.toString());
        assertThat(trail).contains("UPLOAD_DOCUMENT");

        // sources untouched
        assertThat(PdfTestFiles.pageTexts(download(contributor, a))).containsExactly("A1", "A2", "A3");
    }

    @Test
    void mergeWithPageSelectionsAndExplicitName() {
        UUID a = upload(contributor, "alpha.pdf", PdfTestFiles.pdf("A1", "A2", "A3"));
        UUID b = upload(contributor, "beta.pdf", PdfTestFiles.pdf("B1", "B2"));
        PdfOperationResponse r = ok(post(contributor, "/merge",
                new MergeRequest(List.of(new MergeSource(b, "2"), new MergeSource(a, "1,3"), new MergeSource(b, "1")),
                        null, newDocument("bundle"))));
        PdfOutputInfo out = r.outputs().getFirst();
        assertThat(out.name()).isEqualTo("bundle.pdf");
        assertThat(PdfTestFiles.pageTexts(download(contributor, out.documentId()))).containsExactly("B2", "A1", "A3", "B1");
    }

    @Test
    void mergeIntoTheFirstSourceAsANewVersion() {
        UUID a = upload(contributor, "alpha.pdf", PdfTestFiles.pdf("A1"));
        UUID b = upload(contributor, "beta.pdf", PdfTestFiles.pdf("B1"));
        PdfOperationResponse r = ok(post(contributor, "/merge",
                new MergeRequest(List.of(new MergeSource(a, null), new MergeSource(b, null)), null,
                        new OutputTarget(OutputMode.NEW_VERSION, null, null, null, null))));
        assertThat(r.outputs().getFirst().documentId()).isEqualTo(a);
        assertThat(PdfTestFiles.pageTexts(download(contributor, a))).containsExactly("A1", "B1");
        assertThat(auditTrail(a)).contains("PDF_TRANSFORM").contains("REPLACE_DOCUMENT_CONTENT");
    }

    // ── split ───────────────────────────────────────────────────────────────

    @Test
    void splitEveryNPagesIntoNamedParts() {
        UUID id = upload(contributor, "report.pdf", PdfTestFiles.pdf("R1", "R2", "R3", "R4", "R5"));
        PdfOperationResponse r = ok(post(contributor, "/split",
                new SplitRequest(id, SplitMode.EVERY_N_PAGES, 2, null, null, null, null)));
        assertThat(r.operation()).isEqualTo("split");
        assertThat(r.outputs()).extracting(PdfOutputInfo::name).containsExactly("report-1.pdf", "report-2.pdf", "report-3.pdf");
        assertThat(r.outputs()).extracting(PdfOutputInfo::pageCount).containsExactly(2, 2, 1);
        assertThat(PdfTestFiles.pageTexts(download(contributor, r.outputs().get(1).documentId()))).containsExactly("R3", "R4");
        assertThat(PdfTestFiles.pageTexts(download(contributor, id))).hasSize(5);
        assertThat(auditTrail(r.outputs().get(2).documentId())).contains("\"operation\":\"split\"").contains(id.toString());
    }

    @Test
    void splitAtPagesRangesAndEveryPage() {
        UUID id = upload(contributor, "doc.pdf", PdfTestFiles.pdf("D1", "D2", "D3", "D4"));
        PdfOperationResponse at = ok(post(contributor, "/split",
                new SplitRequest(id, SplitMode.AT_PAGES, null, List.of(2, 4), null, null, new SplitOutput(null, "{name} {first}-{last}", null, true))));
        assertThat(at.outputs()).extracting(PdfOutputInfo::name).containsExactly("doc 1-1.pdf", "doc 2-3.pdf", "doc 4-4.pdf");

        PdfOperationResponse ranges = ok(post(contributor, "/split",
                new SplitRequest(id, SplitMode.PAGE_RANGES, null, null, List.of("4,1", "2-3"), null, new SplitOutput(null, null, null, true))));
        assertThat(ranges.outputs()).hasSize(2);
        assertThat(PdfTestFiles.pageTexts(download(contributor, ranges.outputs().getFirst().documentId()))).containsExactly("D4", "D1");

        PdfOperationResponse each = ok(post(contributor, "/split",
                new SplitRequest(id, SplitMode.EVERY_PAGE, null, null, null, null, new SplitOutput(null, null, null, true))));
        assertThat(each.outputs()).hasSize(4);
    }

    @Test
    void splitByBookmarksIntoASubfolder() {
        UUID id = upload(contributor, "thesis.pdf",
                PdfTestFiles.pdf(List.of("T1", "T2", "T3", "T4"), Map.of(2, "Chapter One", 4, "Chapter Two")));
        PdfOperationResponse r = ok(post(contributor, "/split",
                new SplitRequest(id, SplitMode.BY_OUTLINE_LEVEL, null, null, null, 1, new SplitOutput(null, "{index} {title}", true, null))));
        assertThat(r.outputs()).extracting(PdfOutputInfo::name).containsExactly("1 1.pdf", "2 Chapter One.pdf", "3 Chapter Two.pdf");
        assertThat(r.outputs()).extracting(PdfOutputInfo::pageCount).containsExactly(1, 2, 1);

        DocumentInfo part = documentInfo(contributor, r.outputs().get(1).documentId());
        assertThat(part.parentId()).isNotNull();
        DocumentInfo folder = documentInfo(contributor, part.parentId());
        assertThat(folder.name()).isEqualTo("thesis");
        assertThat(folder.parentId()).isEqualTo(documentInfo(contributor, id).parentId());
    }

    @Test
    void splitRefusesTooManyOutputs() {
        UUID id = upload(contributor, "long.pdf", PdfTestFiles.pdf("1", "2", "3", "4", "5"));
        post(contributor, "/split", new SplitRequest(id, SplitMode.EVERY_PAGE, null, null, null, null, null))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(m -> assertThat((String) m).startsWith("TOO_MANY_OUTPUTS"));
    }

    // ── organize ────────────────────────────────────────────────────────────

    @Test
    void organizeReordersRotatesAndDeletesInPlaceByDefault() {
        UUID id = upload(contributor, "pages.pdf", PdfTestFiles.pdf("P1", "P2", "P3"));
        PdfOperationResponse r = ok(post(contributor, "/organize", new OrganizeRequest(id, List.of(
                new PageInstruction(null, 3, 0), new PageInstruction(null, 1, 90)), null)));
        assertThat(r.outputs().getFirst().documentId()).isEqualTo(id);
        assertThat(r.outputs().getFirst().pageCount()).isEqualTo(2);
        byte[] bytes = download(contributor, id);
        assertThat(PdfTestFiles.pageTexts(bytes)).containsExactly("P3", "P1");
        assertThat(PdfTestFiles.rotation(bytes, 2)).isEqualTo(90);
        assertThat(info(contributor, id).pageCount()).isEqualTo(2);
        assertThat(auditTrail(id)).contains("\"operation\":\"organize\"");
    }

    @Test
    void organizeAndRotateKeepTheBookmarksOfSurvivingPages() {
        UUID id = upload(contributor, "chapters.pdf",
                PdfTestFiles.pdf(List.of("C1", "C2", "C3"), Map.of(1, "Intro", 2, "Middle", 3, "End")));
        ok(post(contributor, "/organize", new OrganizeRequest(id, List.of(
                new PageInstruction(null, 3, 0), new PageInstruction(null, 1, 0)), null)));
        PdfInfo reordered = info(contributor, id);
        assertThat(reordered.outline()).extracting(PdfOutlineEntry::title).containsExactly("End", "Intro");
        assertThat(reordered.outline()).extracting(PdfOutlineEntry::page).containsExactly(1, 2);

        ok(post(contributor, "/rotate", new RotateRequest(List.of(id), 90, null, null)));
        assertThat(info(contributor, id).outline()).extracting(PdfOutlineEntry::title).containsExactly("End", "Intro");
        assertThat(PdfTestFiles.pageTexts(download(contributor, id))).containsExactly("C3", "C1");
    }

    @Test
    void organizeExtractsToANewDocumentAndInsertsFromAnotherPdf() {
        UUID main = upload(contributor, "main.pdf", PdfTestFiles.pdf("M1", "M2", "M3"));
        UUID other = upload(contributor, "other.pdf", PdfTestFiles.pdf("O1"));
        PdfOperationResponse r = ok(post(contributor, "/organize", new OrganizeRequest(main, List.of(
                new PageInstruction(null, 2, 0), new PageInstruction(other, 1, 180), new PageInstruction(null, 2, 0)),
                newDocument("extract"))));
        PdfOutputInfo out = r.outputs().getFirst();
        assertThat(out.documentId()).isNotEqualTo(main);
        assertThat(out.name()).isEqualTo("extract.pdf");
        byte[] bytes = download(contributor, out.documentId());
        assertThat(PdfTestFiles.pageTexts(bytes)).containsExactly("M2", "O1", "M2");
        assertThat(PdfTestFiles.rotation(bytes, 2)).isEqualTo(180);
        assertThat(PdfTestFiles.pageTexts(download(contributor, main))).hasSize(3);
        assertThat(auditTrail(out.documentId())).contains(main.toString()).contains(other.toString());
    }

    /**
     * What the organizer dialog sends when every page comes from the edited PDF and one of them
     * was rotated in the grid: the new document must carry that rotation, the source stays intact.
     */
    @Test
    void organizeKeepsPerPageRotationWhenSavingTheMainDocumentAsANewDocument() {
        UUID id = upload(contributor, "cv.pdf", PdfTestFiles.pdf("P1", "P2", "P3"));
        PdfOperationResponse r = ok(post(contributor, "/organize", new OrganizeRequest(id, List.of(
                new PageInstruction(null, 1, 0), new PageInstruction(null, 2, 180), new PageInstruction(null, 3, 0)),
                newDocument("cv (edited)"))));
        UUID created = r.outputs().getFirst().documentId();
        assertThat(created).isNotEqualTo(id);
        byte[] bytes = download(contributor, created);
        assertThat(PdfTestFiles.pageTexts(bytes)).containsExactly("P1", "P2", "P3");
        assertThat(PdfTestFiles.rotation(bytes, 1)).isZero();
        assertThat(PdfTestFiles.rotation(bytes, 2)).isEqualTo(180);
        assertThat(PdfTestFiles.rotation(bytes, 3)).isZero();
        assertThat(PdfTestFiles.rotation(download(contributor, id), 2)).isZero();
    }

    // ── rotate ──────────────────────────────────────────────────────────────

    @Test
    void rotateSelectedPagesOfSeveralDocuments() {
        UUID a = upload(contributor, "scan-a.pdf", PdfTestFiles.pdf("S1", "S2", "S3"));
        UUID b = upload(contributor, "scan-b.pdf", PdfTestFiles.pdf("T1", "T2"));
        PdfOperationResponse r = ok(post(contributor, "/rotate", new RotateRequest(List.of(a, b), -90, "odd", null)));
        assertThat(r.outputs()).extracting(PdfOutputInfo::documentId).containsExactly(a, b);
        byte[] bytesA = download(contributor, a);
        assertThat(PdfTestFiles.rotation(bytesA, 1)).isEqualTo(270);
        assertThat(PdfTestFiles.rotation(bytesA, 2)).isZero();
        assertThat(PdfTestFiles.rotation(bytesA, 3)).isEqualTo(270);
        assertThat(PdfTestFiles.rotation(download(contributor, b), 1)).isEqualTo(270);
        assertThat(info(contributor, a).pages().getFirst().rotation()).isEqualTo(270);
    }

    @Test
    void rotateAsNewDocumentsKeepsTheOriginals() {
        UUID a = upload(contributor, "orig.pdf", PdfTestFiles.pdf("X1"));
        PdfOperationResponse r = ok(post(contributor, "/rotate", new RotateRequest(List.of(a), 180, null, newDocument(null))));
        PdfOutputInfo out = r.outputs().getFirst();
        assertThat(out.documentId()).isNotEqualTo(a);
        assertThat(out.name()).isEqualTo("orig (rotated).pdf");
        assertThat(PdfTestFiles.rotation(download(contributor, out.documentId()), 1)).isEqualTo(180);
        assertThat(PdfTestFiles.rotation(download(contributor, a), 1)).isZero();
    }

    // ── guards & roles ──────────────────────────────────────────────────────

    @Test
    void readerCannotTransform() {
        UUID id = upload(contributor, "ro.pdf", PdfTestFiles.pdf("R"));
        post(reader, "/rotate", new RotateRequest(List.of(id), 90, null, null)).expectStatus().isForbidden();
        post(reader, "/merge", new MergeRequest(List.of(new MergeSource(id, null)), null, null)).expectStatus().isForbidden();
        post(reader, "/split", new SplitRequest(id, SplitMode.EVERY_PAGE, null, null, null, null, null)).expectStatus().isForbidden();
        post(reader, "/organize", new OrganizeRequest(id, List.of(new PageInstruction(null, 1, 0)), null)).expectStatus().isForbidden();
        getWebTestClient().get().uri(PDF + "/" + id + "/info").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void inputGuards() {
        UUID txt = upload(contributor, "notes.txt", "hello".getBytes());
        getWebTestClient().get().uri(PDF + "/" + txt + "/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(m -> assertThat((String) m).startsWith("NOT_A_PDF"));

        getWebTestClient().get().uri(PDF + "/" + UUID.randomUUID() + "/info")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNotFound();

        UUID locked = upload(contributor, "locked.pdf", PdfTestFiles.encryptedPdf("pw"));
        assertThat(info(contributor, locked).encrypted()).isTrue();
        post(contributor, "/rotate", new RotateRequest(List.of(locked), 90, null, null))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(m -> assertThat((String) m).startsWith("PDF_ENCRYPTED"));

        UUID id = upload(contributor, "guard.pdf", PdfTestFiles.pdf("G1", "G2"));
        post(contributor, "/rotate", new RotateRequest(List.of(id), 90, "5", null)).expectStatus().isBadRequest();
        post(contributor, "/rotate", new RotateRequest(List.of(id), 45, null, null)).expectStatus().isBadRequest();
        post(contributor, "/organize", new OrganizeRequest(id, List.of(new PageInstruction(null, 3, 0)), null)).expectStatus().isBadRequest();
        post(contributor, "/organize", new OrganizeRequest(id, List.of(), null)).expectStatus().isBadRequest();
        post(contributor, "/split", new SplitRequest(id, SplitMode.EVERY_N_PAGES, 0, null, null, null, null)).expectStatus().isBadRequest();
        post(contributor, "/split", new SplitRequest(id, SplitMode.BY_OUTLINE_LEVEL, null, null, null, null, null))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(m -> assertThat((String) m).startsWith("PDF_NO_OUTLINE"));
        post(contributor, "/merge", "{\"sources\":null}").expectStatus().isBadRequest();
    }

    /** Small holder so the document path constant reads well above. */
    private static final class RestApiClientPaths {
        static final String DOCS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;
    }
}
