package org.openfilz.dms.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ThumbnailServiceImpl} covering the dispatch branches and the
 * private rendering helpers (fallback placeholder, text rendering, malformed-PDF handling)
 * that the happy-path integration tests don't reach.
 */
@ExtendWith(MockitoExtension.class)
class ThumbnailServiceImplTest {

    @Mock
    private ThumbnailStorageService thumbnailStorage;
    @Mock
    private ThumbnailProperties thumbnailProperties;
    @Mock
    private WebClient.Builder webClientBuilder;
    @Mock
    private StorageService storageService;

    private ThumbnailServiceImpl service() {
        return new ThumbnailServiceImpl(thumbnailStorage, thumbnailProperties, webClientBuilder, storageService);
    }

    private static byte[] pdfBytes(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Document doc(String contentType, Long size) {
        return Document.builder()
                .id(UUID.randomUUID())
                .name("file")
                .contentType(contentType)
                .size(size)
                .storagePath("path/obj")
                .build();
    }

    // ==================== generateThumbnail dispatch branches ====================

    @Test
    void generateThumbnail_unsupportedContentType_completesEmpty() {
        when(thumbnailProperties.isContentTypeSupported("application/zip")).thenReturn(false);

        StepVerifier.create(service().generateThumbnail(doc("application/zip", 10L)))
                .verifyComplete();
        verifyNoInteractions(storageService, thumbnailStorage);
    }

    @Test
    void generateThumbnail_pdfTooLarge_skips() {
        when(thumbnailProperties.isContentTypeSupported("application/pdf")).thenReturn(true);
        when(thumbnailProperties.shouldUseWebpConversion("application/pdf")).thenReturn(false);
        when(thumbnailProperties.shouldUsePdfBox("application/pdf")).thenReturn(true);
        when(thumbnailProperties.getPdfMaxSizeBytes()).thenReturn(1_000L);

        StepVerifier.create(service().generateThumbnail(doc("application/pdf", 5_000L)))
                .verifyComplete();
        verifyNoInteractions(storageService);
    }

    @Test
    void generateThumbnail_noHandlerForSupportedType_completesEmpty() {
        when(thumbnailProperties.isContentTypeSupported("text/x-weird")).thenReturn(true);
        when(thumbnailProperties.shouldUseWebpConversion("text/x-weird")).thenReturn(false);
        when(thumbnailProperties.shouldUsePdfBox("text/x-weird")).thenReturn(false);
        when(thumbnailProperties.shouldUseGotenberg("text/x-weird")).thenReturn(false);
        when(thumbnailProperties.shouldUseTextRenderer("text/x-weird")).thenReturn(false);

        StepVerifier.create(service().generateThumbnail(doc("text/x-weird", 10L)))
                .verifyComplete();
    }

    @Test
    void generateThumbnail_loadFailure_isResumedAndSwallowed() {
        when(thumbnailProperties.isContentTypeSupported("image/png")).thenReturn(true);
        when(thumbnailProperties.shouldUseWebpConversion("image/png")).thenReturn(true);
        when(storageService.loadFile("path/obj")).thenReturn(Mono.error(new RuntimeException("storage down")));

        StepVerifier.create(service().generateThumbnail(doc("image/png", 10L)))
                .verifyComplete();
    }

    @Test
    void generateThumbnail_pdfWithNoPages_isResumedAndSwallowed() {
        when(thumbnailProperties.isContentTypeSupported("application/pdf")).thenReturn(true);
        when(thumbnailProperties.shouldUseWebpConversion("application/pdf")).thenReturn(false);
        when(thumbnailProperties.shouldUsePdfBox("application/pdf")).thenReturn(true);
        when(thumbnailProperties.getPdfMaxSizeBytes()).thenReturn(10_000_000L);
        doReturn(Mono.just(new ByteArrayResource(pdfBytes(0))))
                .when(storageService).loadFile("path/obj");

        StepVerifier.create(service().generateThumbnail(doc("application/pdf", 100L)))
                .verifyComplete();
    }

    // ==================== private rendering helpers ====================

    @Test
    void renderPdfThumbnail_withNoPages_errors() {
        Mono<byte[]> result = ReflectionTestUtils.invokeMethod(service(), "renderPdfThumbnail", (Object) pdfBytes(0));

        StepVerifier.create(result)
                .expectError()
                .verify();
    }

    @Test
    void createPdfFallbackImage_producesProportionedPlaceholder() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());

            BufferedImage image = ReflectionTestUtils.invokeMethod(service(), "createPdfFallbackImage", pdf);

            assertNotNull(image);
            assertTrue(image.getWidth() >= 200);
            assertTrue(image.getHeight() >= 200);
        }
    }

    @Test
    void renderTextToImage_withManyLines_drawsTruncationIndicator() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add("line " + i);
        }

        BufferedImage image = ReflectionTestUtils.invokeMethod(service(), "renderTextToImage", lines, 200, 800);

        assertNotNull(image);
        assertEquals(200, image.getWidth());
        assertEquals(800, image.getHeight());
    }

    // ==================== simple delegations ====================

    @Test
    void getThumbnail_delegatesToStorage() {
        UUID id = UUID.randomUUID();
        when(thumbnailStorage.loadThumbnail(id)).thenReturn(Mono.just(new byte[]{1, 2, 3}));

        StepVerifier.create(service().getThumbnail(id))
                .expectNextMatches(b -> b.length == 3)
                .verifyComplete();
    }

    @Test
    void deleteThumbnail_delegatesToStorage() {
        UUID id = UUID.randomUUID();
        when(thumbnailStorage.deleteThumbnail(id)).thenReturn(Mono.empty());

        StepVerifier.create(service().deleteThumbnail(id)).verifyComplete();
        verify(thumbnailStorage).deleteThumbnail(id);
    }

    @Test
    void copyThumbnail_delegatesToStorage() {
        UUID src = UUID.randomUUID();
        UUID dst = UUID.randomUUID();
        when(thumbnailStorage.copyThumbnail(src, dst)).thenReturn(Mono.empty());

        StepVerifier.create(service().copyThumbnail(src, dst)).verifyComplete();
        verify(thumbnailStorage).copyThumbnail(src, dst);
    }

    @Test
    void isSupported_delegatesToProperties() {
        when(thumbnailProperties.isContentTypeSupported("image/png")).thenReturn(true);
        assertTrue(service().isSupported("image/png"));
    }
}
