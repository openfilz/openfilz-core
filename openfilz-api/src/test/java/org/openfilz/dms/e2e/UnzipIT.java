package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.UnzipRequest;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UnzipResponse;
import org.openfilz.dms.dto.response.UnzipResponse.SkipReason;
import org.openfilz.dms.dto.response.UnzipResponse.UnzipSkippedEntry;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * POST /files/{id}/unzip through the REST API: destinations (ZIP's folder, chosen folder, new
 * folder, root), folder tree recreation (explicit and implicit folders), merge into existing
 * folders, name clashes, unsafe entries, and the refusals (not a ZIP, unreadable ZIP, unknown
 * destination). Runs on local storage; {@link UnzipMinioIT} replays it on MinIO.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class UnzipIT extends TestContainersBaseConfig {

    public UnzipIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    // ==================== Destinations ====================

    @Test
    void whenNoDestination_thenExtractsIntoTheZipFolderWithItsTree() throws IOException {
        FolderResponse folder = createFolder("unzip-same-" + UUID.randomUUID(), null);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("readme.txt", "hello");
        entries.put("docs/", null);
        entries.put("docs/guide.md", "# guide");
        entries.put("docs/img/logo.svg", "<svg/>");   // implicit folder "docs/img"
        entries.put("empty/", null);
        UploadResponse zip = uploadZip("archive.zip", zip(entries), folder.id());

        UnzipResponse response = unzip(zip.id(), new UnzipRequest(null, null, null, null));

        assertThat(response.targetFolderId()).isEqualTo(folder.id());
        assertThat(response.createdFolderId()).isNull();
        assertThat(response.filesExtracted()).isEqualTo(3);
        assertThat(response.foldersCreated()).isEqualTo(3);
        assertThat(response.skipped()).isEmpty();

        Map<String, FolderElementInfo> top = list(folder.id());
        assertThat(top.keySet()).containsExactlyInAnyOrder("archive.zip", "readme.txt", "docs", "empty");
        assertThat(top.get("docs").type()).isEqualTo(DocumentType.FOLDER);
        Map<String, FolderElementInfo> docs = list(top.get("docs").id());
        assertThat(docs.keySet()).containsExactlyInAnyOrder("guide.md", "img");
        Map<String, FolderElementInfo> img = list(docs.get("img").id());
        assertThat(img.keySet()).containsExactly("logo.svg");
        assertThat(list(top.get("empty").id())).isEmpty();

        assertThat(download(top.get("readme.txt").id())).isEqualTo("hello");
        assertThat(download(img.get("logo.svg").id())).isEqualTo("<svg/>");
    }

    @Test
    void whenNewFolderName_thenCreatesItInTheChosenFolder() throws IOException {
        FolderResponse source = createFolder("unzip-src-" + UUID.randomUUID(), null);
        FolderResponse target = createFolder("unzip-dst-" + UUID.randomUUID(), null);
        UploadResponse zip = uploadZip("photos.zip", zip(Map.of("a.txt", "A", "sub/b.txt", "B")), source.id());

        UnzipResponse response = unzip(zip.id(), new UnzipRequest(target.id(), null, "Photos 2026", null));

        assertThat(response.targetFolderId()).isEqualTo(target.id());
        assertThat(response.createdFolderId()).isNotNull();
        assertThat(response.filesExtracted()).isEqualTo(2);
        assertThat(response.foldersCreated()).isEqualTo(2); // "Photos 2026" + "sub"

        Map<String, FolderElementInfo> dst = list(target.id());
        assertThat(dst.keySet()).containsExactly("Photos 2026");
        assertThat(dst.get("Photos 2026").id()).isEqualTo(response.createdFolderId());
        assertThat(list(response.createdFolderId()).keySet()).containsExactlyInAnyOrder("a.txt", "sub");
        assertThat(list(source.id()).keySet()).containsExactly("photos.zip"); // source folder untouched
    }

    @Test
    void whenNewFolderNameAlreadyExists_thenConflictAndNothingIsWritten() throws IOException {
        FolderResponse folder = createFolder("unzip-clash-" + UUID.randomUUID(), null);
        createFolder("out", folder.id());
        UploadResponse zip = uploadZip("x.zip", zip(Map.of("a.txt", "A")), folder.id());

        exchangeUnzip(zip.id(), new UnzipRequest(null, null, "out", null)).expectStatus().isEqualTo(409);

        assertThat(list(folder.id()).keySet()).containsExactlyInAnyOrder("x.zip", "out");
    }

    @Test
    void whenTargetRoot_thenExtractsAtTheRoot() throws IOException {
        FolderResponse folder = createFolder("unzip-root-" + UUID.randomUUID(), null);
        String unique = "root-unzip-" + UUID.randomUUID();
        UploadResponse zip = uploadZip("r.zip", zip(Map.of(unique + "/f.txt", "F")), folder.id());

        UnzipResponse response = unzip(zip.id(), new UnzipRequest(folder.id(), true, null, null));

        assertThat(response.targetFolderId()).isNull();
        assertThat(list(null)).containsKey(unique);
    }

    @Test
    void whenNoBody_thenExtractsIntoTheZipFolder() throws IOException {
        FolderResponse folder = createFolder("unzip-nobody-" + UUID.randomUUID(), null);
        UploadResponse zip = uploadZip("n.zip", zip(Map.of("n.txt", "N")), folder.id());

        getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/{id}/unzip", zip.id())
                .exchange()
                .expectStatus().isOk();

        assertThat(list(folder.id())).containsKey("n.txt");
    }

    // ==================== Merge & clashes ====================

    @Test
    void whenExtractedTwice_thenFoldersAreMergedAndExistingFilesSkipped() throws IOException {
        FolderResponse folder = createFolder("unzip-twice-" + UUID.randomUUID(), null);
        UploadResponse zip = uploadZip("t.zip", zip(Map.of("a.txt", "A", "dir/b.txt", "B")), folder.id());
        unzip(zip.id(), new UnzipRequest(null, null, null, null));

        UnzipResponse second = unzip(zip.id(), new UnzipRequest(null, null, null, null));

        assertThat(second.filesExtracted()).isZero();
        assertThat(second.foldersCreated()).isZero();
        assertThat(second.skipped()).extracting(UnzipSkippedEntry::path, UnzipSkippedEntry::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("a.txt", SkipReason.DUPLICATE_NAME),
                        org.assertj.core.groups.Tuple.tuple("dir/b.txt", SkipReason.DUPLICATE_NAME));
        assertThat(list(folder.id()).keySet()).containsExactlyInAnyOrder("t.zip", "a.txt", "dir");
    }

    @Test
    void whenMergingIntoAnExistingFolder_thenOnlyNewFilesAreAdded() throws IOException {
        FolderResponse folder = createFolder("unzip-merge-" + UUID.randomUUID(), null);
        FolderResponse dir = createFolder("dir", folder.id());
        uploadFile("old.txt", "old".getBytes(StandardCharsets.UTF_8), dir.id());
        UploadResponse zip = uploadZip("m.zip", zip(Map.of("dir/old.txt", "new", "dir/new.txt", "N")), folder.id());

        UnzipResponse response = unzip(zip.id(), new UnzipRequest(null, null, null, null));

        assertThat(response.foldersCreated()).isZero();
        assertThat(response.filesExtracted()).isEqualTo(1);
        assertThat(response.skipped()).extracting(UnzipSkippedEntry::path).containsExactly("dir/old.txt");
        Map<String, FolderElementInfo> content = list(dir.id());
        assertThat(content.keySet()).containsExactlyInAnyOrder("old.txt", "new.txt");
        assertThat(download(content.get("old.txt").id())).isEqualTo("old");
    }

    @Test
    void whenAllowDuplicateFileNames_thenClashingFilesAreStillExtracted() throws IOException {
        FolderResponse folder = createFolder("unzip-dup-" + UUID.randomUUID(), null);
        UploadResponse zip = uploadZip("d.zip", zip(Map.of("a.txt", "A")), folder.id());
        unzip(zip.id(), new UnzipRequest(null, null, null, null));

        UnzipResponse second = unzip(zip.id(), new UnzipRequest(null, null, null, true));

        assertThat(second.filesExtracted()).isEqualTo(1);
        assertThat(second.skipped()).isEmpty();
    }

    @Test
    void whenAFileBlocksAFolderName_thenItsSubtreeIsSkipped() throws IOException {
        FolderResponse folder = createFolder("unzip-block-" + UUID.randomUUID(), null);
        uploadFile("dir", "I am a file".getBytes(StandardCharsets.UTF_8), folder.id());
        UploadResponse zip = uploadZip("b.zip", zip(Map.of("dir/x.txt", "X", "ok.txt", "OK")), folder.id());

        UnzipResponse response = unzip(zip.id(), new UnzipRequest(null, null, null, null));

        assertThat(response.filesExtracted()).isEqualTo(1);
        assertThat(response.skipped()).extracting(UnzipSkippedEntry::path, UnzipSkippedEntry::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("dir", SkipReason.DUPLICATE_NAME),
                        org.assertj.core.groups.Tuple.tuple("dir/x.txt", SkipReason.PARENT_NOT_CREATED));
    }

    // ==================== Unsafe & junk entries ====================

    @Test
    void whenEntriesTryToEscape_thenTheyAreSkippedAndJunkIsIgnored() throws IOException {
        FolderResponse folder = createFolder("unzip-slip-" + UUID.randomUUID(), null);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", "evil");
        entries.put("a/../../evil2.txt", "evil");
        entries.put("C:/windows.txt", "evil");
        entries.put("__MACOSX/._good.txt", "junk");
        entries.put("sub/.DS_Store", "junk");
        entries.put("./good.txt", "good");
        entries.put("win\\path.txt", "backslash");
        UploadResponse zip = uploadZip("s.zip", zip(entries), folder.id());

        UnzipResponse response = unzip(zip.id(), new UnzipRequest(null, null, null, null));

        assertThat(response.skipped()).extracting(UnzipSkippedEntry::reason).containsOnly(SkipReason.UNSAFE_PATH);
        assertThat(response.skipped()).hasSize(3);
        assertThat(list(folder.id()).keySet()).containsExactlyInAnyOrder("s.zip", "good.txt", "win");
    }

    // ==================== Refusals ====================

    @Test
    void whenNotAZip_thenUnprocessable() {
        FolderResponse folder = createFolder("unzip-notzip-" + UUID.randomUUID(), null);
        UploadResponse file = uploadFile("note.txt", "plain".getBytes(StandardCharsets.UTF_8), folder.id());

        exchangeUnzip(file.id(), new UnzipRequest(null, null, null, null))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(m -> assertThat(m.toString()).startsWith("NOT_A_ZIP"));
    }

    @Test
    void whenCorruptZip_thenUnprocessable() {
        FolderResponse folder = createFolder("unzip-corrupt-" + UUID.randomUUID(), null);
        UploadResponse zip = uploadZip("broken.zip", "not a zip at all".getBytes(StandardCharsets.UTF_8), folder.id());

        exchangeUnzip(zip.id(), new UnzipRequest(null, null, null, null))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.message").value(m -> assertThat(m.toString()).startsWith("ZIP_INVALID"));
        assertThat(list(folder.id()).keySet()).containsExactly("broken.zip");
    }

    @Test
    void whenUnknownDocumentOrTarget_thenNotFound() throws IOException {
        exchangeUnzip(UUID.randomUUID(), new UnzipRequest(null, null, null, null)).expectStatus().isNotFound();

        FolderResponse folder = createFolder("unzip-404-" + UUID.randomUUID(), null);
        UploadResponse zip = uploadZip("z.zip", zip(Map.of("a.txt", "A")), folder.id());
        exchangeUnzip(zip.id(), new UnzipRequest(UUID.randomUUID(), null, null, null)).expectStatus().isNotFound();
    }

    // ==================== Helpers ====================

    protected static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (e.getValue() != null) {
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    protected UploadResponse uploadZip(String name, byte[] bytes, UUID folderId) {
        return uploadFile(name, bytes, folderId);
    }

    protected UploadResponse uploadFile(String name, byte[] bytes, UUID folderId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        if (folderId != null) {
            builder.part("parentFolderId", folderId.toString());
        }
        return getUploadResponse(builder, false);
    }

    protected UnzipResponse unzip(UUID zipId, UnzipRequest request) {
        return exchangeUnzip(zipId, request)
                .expectStatus().isOk()
                .expectBody(UnzipResponse.class)
                .returnResult().getResponseBody();
    }

    protected WebTestClient.ResponseSpec exchangeUnzip(UUID zipId, UnzipRequest request) {
        return getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/files/{id}/unzip", zipId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange();
    }

    protected Map<String, FolderElementInfo> list(UUID folderId) {
        List<FolderElementInfo> content = getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParamIfPresent("folderId", Optional.ofNullable(folderId))
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();
        return content.stream().collect(Collectors.toMap(FolderElementInfo::name, e -> e, (a, _) -> a));
    }

    protected String download(UUID documentId) {
        byte[] body = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", documentId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
        return new String(body, StandardCharsets.UTF_8);
    }

    protected FolderResponse createFolder(String name, UUID parentId) {
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(new CreateFolderRequest(name, parentId)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }
}
