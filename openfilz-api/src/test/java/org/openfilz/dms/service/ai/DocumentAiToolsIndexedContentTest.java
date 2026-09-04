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
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.StorageService;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code readDocumentContent} serves the text the full-text indexing pass already extracted
 * when an index is available, and only downloads + parses the file when the index has nothing
 * — so the model reading N files to classify them costs N index lookups, not N Tika runs.
 */
class DocumentAiToolsIndexedContentTest {

    private final UUID id = UUID.randomUUID();
    private final Document doc = Document.builder()
            .id(id).name("report.txt").type(DocumentType.FILE).active(true).storagePath("report.txt").build();
    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final StorageService storage = mock(StorageService.class);

    private DocumentAiTools tools(IndexService indexService) {
        when(repository.findById(id)).thenReturn(Mono.just(doc));
        return new DocumentAiTools(
                mock(DocumentService.class), repository, storage, mock(AiDocumentQueryService.class),
                null, new PermitAllAiAccessPolicy(), (authentication, capability) -> true,
                mock(DocumentVersionService.class), new CommonProperties(),
                new DownloadTokenService(new DownloadTokenProperties()),
                null, indexService, null, null);
    }

    @Test
    @DisplayName("indexed text is served without touching storage")
    void servesIndexedTextWithoutStorage() {
        IndexService index = mock(IndexService.class);
        when(index.getContent(id)).thenReturn(Mono.just("Quarterly figures, indexed at upload."));

        String result = tools(index).readDocumentContent(id.toString(), null);

        assertThat(result).contains("Content of 'report.txt'").contains("Quarterly figures, indexed at upload.");
        verify(storage, never()).loadFile(any());
    }

    @Test
    @DisplayName("an empty index answer falls back to the file")
    void fallsBackToTheFileWhenTheIndexHasNothing() {
        IndexService index = mock(IndexService.class);
        when(index.getContent(id)).thenReturn(Mono.empty());
        doReturn(Mono.just(new ByteArrayResource("Figures read from the file itself.".getBytes(StandardCharsets.UTF_8))))
                .when(storage).loadFile("report.txt");

        String result = tools(index).readDocumentContent(id.toString(), null);

        assertThat(result).contains("Figures read from the file itself.");
        verify(storage).loadFile("report.txt");
    }

    @Test
    @DisplayName("without an index service (full-text off) the file is read as before")
    void noIndexServiceMeansTheFileIsRead() {
        doReturn(Mono.just(new ByteArrayResource("Plain file text.".getBytes(StandardCharsets.UTF_8))))
                .when(storage).loadFile("report.txt");

        String result = tools(null).readDocumentContent(id.toString(), null);

        assertThat(result).contains("Plain file text.");
    }

    @Test
    @DisplayName("indexed text is truncated to the same budget as parsed text")
    void indexedTextIsTruncatedLikeParsedText() {
        IndexService index = mock(IndexService.class);
        when(index.getContent(id)).thenReturn(Mono.just("x".repeat(DocumentAiTools.MAX_CONTENT_CHARS + 500)));

        String result = tools(index).readDocumentContent(id.toString(), null);

        assertThat(result).contains("[... content truncated, document is longer ...]");
        assertThat(result.length()).isLessThan(DocumentAiTools.MAX_CONTENT_CHARS + 200);
    }
}
