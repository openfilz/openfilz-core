package org.openfilz.dms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThumbnailUrlResolverTest {

    @Mock
    private CommonProperties commonProperties;

    @Mock
    private ThumbnailProperties thumbnailProperties;

    @Mock
    private ThumbnailStorageService thumbnailStorageService;

    private ThumbnailUrlResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ThumbnailUrlResolver(commonProperties);
    }

    @Test
    void resolveThumbnailUrl_whenThumbnailPropertiesIsNull_returnsEmpty() {
        // thumbnailProperties is null by default (not injected)
        StepVerifier.create(resolver.resolveThumbnailUrl(UUID.randomUUID(), DocumentType.FILE, "image/png"))
                .verifyComplete();
    }

    @Test
    void resolveThumbnailUrl_whenStorageServiceIsNull_returnsEmpty() {
        ReflectionTestUtils.setField(resolver, "thumbnailProperties", thumbnailProperties);
        // thumbnailStorageService remains null

        StepVerifier.create(resolver.resolveThumbnailUrl(UUID.randomUUID(), DocumentType.FILE, "image/png"))
                .verifyComplete();
    }

    @Test
    void resolveThumbnailUrl_whenDocumentIsFolder_returnsEmpty() {
        ReflectionTestUtils.setField(resolver, "thumbnailProperties", thumbnailProperties);
        ReflectionTestUtils.setField(resolver, "thumbnailStorageService", thumbnailStorageService);

        StepVerifier.create(resolver.resolveThumbnailUrl(UUID.randomUUID(), DocumentType.FOLDER, "image/png"))
                .verifyComplete();
    }

    @Test
    void resolveThumbnailUrl_whenContentTypeNotSupported_returnsEmpty() {
        ReflectionTestUtils.setField(resolver, "thumbnailProperties", thumbnailProperties);
        ReflectionTestUtils.setField(resolver, "thumbnailStorageService", thumbnailStorageService);

        when(thumbnailProperties.isContentTypeSupported("application/zip")).thenReturn(false);

        StepVerifier.create(resolver.resolveThumbnailUrl(UUID.randomUUID(), DocumentType.FILE, "application/zip"))
                .verifyComplete();
    }

    @Test
    void resolveThumbnailUrl_whenThumbnailDoesNotExist_returnsEmpty() {
        UUID docId = UUID.randomUUID();
        ReflectionTestUtils.setField(resolver, "thumbnailProperties", thumbnailProperties);
        ReflectionTestUtils.setField(resolver, "thumbnailStorageService", thumbnailStorageService);

        when(thumbnailProperties.isContentTypeSupported("image/jpeg")).thenReturn(true);
        when(thumbnailStorageService.thumbnailExists(docId)).thenReturn(reactor.core.publisher.Mono.just(false));

        StepVerifier.create(resolver.resolveThumbnailUrl(docId, DocumentType.FILE, "image/jpeg"))
                .verifyComplete();
    }

    @Test
    void resolveThumbnailUrl_whenAllConditionsMet_returnsUrl() {
        UUID docId = UUID.randomUUID();
        ReflectionTestUtils.setField(resolver, "thumbnailProperties", thumbnailProperties);
        ReflectionTestUtils.setField(resolver, "thumbnailStorageService", thumbnailStorageService);

        when(thumbnailProperties.isContentTypeSupported("image/jpeg")).thenReturn(true);
        when(thumbnailStorageService.thumbnailExists(docId)).thenReturn(reactor.core.publisher.Mono.just(true));
        when(commonProperties.getApiPublicBaseUrl()).thenReturn("http://localhost:8081");

        String expectedUrl = "http://localhost:8081/api/v1/thumbnails/img/" + docId;

        StepVerifier.create(resolver.resolveThumbnailUrl(docId, DocumentType.FILE, "image/jpeg"))
                .expectNext(expectedUrl)
                .verifyComplete();
    }
}
