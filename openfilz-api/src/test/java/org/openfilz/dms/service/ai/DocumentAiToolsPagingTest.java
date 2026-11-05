package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.DownloadTokenProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.security.DownloadTokenService;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.service.StorageService;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the paging of {@code readDocumentContent} promises about the cache and the page edges. */
class DocumentAiToolsPagingTest {

    private final UUID id = UUID.randomUUID();
    private final OffsetDateTime updated = OffsetDateTime.parse("2026-11-05T10:15:30Z");
    private final Document doc = Document.builder().id(id).name("long.txt").type(DocumentType.FILE)
            .active(true).storagePath("long.txt").updatedAt(updated).build();
    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final ExtractedTextCache cache = new ExtractedTextCache();
    private final AtomicBoolean allowed = new AtomicBoolean(true);

    private final AiAccessPolicy policy = new AiAccessPolicy() {
        @Override
        public boolean permitAll() {
            return false;
        }

        @Override
        public Mono<Boolean> canRead(UUID documentId, String userEmail) {
            return Mono.just(allowed.get());
        }

        @Override
        public Mono<Boolean> canModify(UUID documentId, String userEmail) {
            return Mono.just(allowed.get());
        }
    };

    private DocumentAiTools tools() {
        when(repository.findById(id)).thenReturn(Mono.just(doc));
        return new DocumentAiTools(
                mock(DocumentService.class), repository, storage, mock(AiDocumentQueryService.class),
                null, policy, (authentication, capability) -> true,
                mock(DocumentVersionService.class), new CommonProperties(),
                new DownloadTokenService(new DownloadTokenProperties()),
                null, null, null, null, null)
                .withTextPaging(1000, cache);
    }

    private void stored(String text) {
        doReturn(Mono.fromSupplier(() -> new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8))))
                .when(storage).loadFile(anyString());
    }

    @Test
    @DisplayName("each page after the first comes from the cache: the file is parsed once")
    void theFileIsParsedOnce() {
        stored("x".repeat(3000));
        DocumentAiTools tools = tools();

        assertThat(tools.readDocumentContent(id.toString(), null, 0, 1000)).contains("call again with offset=1000");
        assertThat(tools.readDocumentContent(id.toString(), null, 1000, 1000)).contains("call again with offset=2000");
        assertThat(tools.readDocumentContent(id.toString(), null, 2000, 1000)).contains("end of the document");

        verify(storage, times(1)).loadFile(anyString());
    }

    @Test
    @DisplayName("a page of a document the caller lost access to is refused, even with the text in the cache")
    void lostAccessIsRefusedWhenCached() {
        stored("confidential ".repeat(300));
        DocumentAiTools tools = tools();
        assertThat(tools.readDocumentContent(id.toString(), null, 0, 100)).contains("confidential");

        allowed.set(false);

        assertThat(tools.readDocumentContent(id.toString(), null, 100, 100))
                .contains("not found").doesNotContain("confidential");
    }

    @Test
    @DisplayName("a new content is not served from the cache")
    void newContentIsNotServedFromTheCache() {
        cache.put(id, updated.minusMinutes(1), "the former text");
        stored("the current text");

        assertThat(tools().readDocumentContent(id.toString(), null)).contains("the current text").doesNotContain("former");
    }

    @Test
    @DisplayName("a page edge never cuts a character outside the basic plane in two")
    void aPageEdgeKeepsSurrogatePairsWhole() {
        String emoji = "😀";
        stored("ab" + emoji + "cd");
        DocumentAiTools tools = tools();

        // Three characters would end inside the emoji: the page stops before it, and the next starts on it
        String first = tools.readDocumentContent(id.toString(), null, 0, 3);
        assertThat(first).contains("[characters 0 to 2 of 6, call again with offset=2]");
        assertThat(tools.readDocumentContent(id.toString(), null, 2, 3)).contains(emoji);
    }

    @Test
    @DisplayName("a document that fits one page carries no range line, and a negative offset reads from the start")
    void shortDocument() {
        stored("short text");

        assertThat(tools().readDocumentContent(id.toString(), null, -5, null))
                .contains("short text").doesNotContain("[characters").endsWith(UntrustedContent.CLOSE_TAG);
        verify(storage, never()).loadFile("other");
    }
}
