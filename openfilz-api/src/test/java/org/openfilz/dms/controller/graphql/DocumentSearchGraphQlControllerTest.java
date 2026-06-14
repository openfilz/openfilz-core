package org.openfilz.dms.controller.graphql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.response.DocumentSearchInfo;
import org.openfilz.dms.dto.response.DocumentSearchResult;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.DocumentSearchService;
import org.openfilz.dms.service.ThumbnailUrlResolver;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentSearchGraphQlControllerTest {

    @Mock
    private DocumentSearchService documentSearchService;
    @Mock
    private ThumbnailUrlResolver thumbnailUrlResolver;

    private DocumentSearchGraphQlController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentSearchGraphQlController(documentSearchService, thumbnailUrlResolver);
    }

    @Test
    void searchDocuments_delegatesToService() {
        DocumentSearchResult result = new DocumentSearchResult(0L, List.of());
        when(documentSearchService.search(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(Mono.just(result));

        StepVerifier.create(controller.searchDocuments("q", null, null, 1, 10, null))
                .expectNext(result)
                .verifyComplete();
    }

    @Test
    void thumbnailUrl_forFolder_usesFolderTypeAndNullContentType() {
        UUID id = UUID.randomUUID();
        DocumentSearchInfo folder = new DocumentSearchInfo(id, "dir", null, null, null, null, null, null, null, null);
        when(thumbnailUrlResolver.resolveThumbnailUrl(id, DocumentType.FOLDER, null))
                .thenReturn(Mono.just("folder-url"));

        StepVerifier.create(controller.documentSearchInfoThumbnailUrl(folder))
                .expectNext("folder-url")
                .verifyComplete();
    }

    @Test
    void thumbnailUrl_forFile_derivesContentTypeFromExtensionWhenMissing() {
        UUID id = UUID.randomUUID();
        DocumentSearchInfo file = new DocumentSearchInfo(id, "report", "pdf", null, 1L, null, null, null, null, null);
        when(thumbnailUrlResolver.resolveThumbnailUrl(id, DocumentType.FILE, "application/pdf"))
                .thenReturn(Mono.just("file-url"));

        StepVerifier.create(controller.documentSearchInfoThumbnailUrl(file))
                .expectNext("file-url")
                .verifyComplete();
    }

    @Test
    void thumbnailUrl_forFile_keepsExplicitContentType() {
        UUID id = UUID.randomUUID();
        DocumentSearchInfo file = new DocumentSearchInfo(id, "img", "png", "image/png", 1L, null, null, null, null, null);
        when(thumbnailUrlResolver.resolveThumbnailUrl(id, DocumentType.FILE, "image/png"))
                .thenReturn(Mono.just("img-url"));

        StepVerifier.create(controller.documentSearchInfoThumbnailUrl(file))
                .expectNext("img-url")
                .verifyComplete();
    }
}
