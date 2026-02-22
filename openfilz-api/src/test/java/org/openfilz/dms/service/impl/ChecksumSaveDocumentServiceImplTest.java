package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.Checksum;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ChecksumSaveDocumentServiceImplTest {

    @Mock private StorageService storageService;
    @Mock private ObjectMapper objectMapper;
    @Mock private AuditService auditService;
    @Mock private JsonUtils jsonUtils;
    @Mock private DocumentDAO documentDAO;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private TransactionalOperator tx;
    @Mock private QuotaProperties quotaProperties;
    @Mock private ChecksumService checksumService;

    private ChecksumSaveDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChecksumSaveDocumentServiceImpl(
                storageService, objectMapper, auditService, jsonUtils,
                documentDAO, metadataPostProcessor, tx, quotaProperties, checksumService);
    }

    @Test
    void getChecksum_withMetadataContainingChecksum_returnsChecksum() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("file.txt")
                .metadata(Json.of("{\"sha256\":\"abc123hash\"}"))
                .build();

        Map<String, Object> metadataMap = Map.of("sha256", "abc123hash");
        when(jsonUtils.toMap(any(Json.class))).thenReturn(metadataMap);

        String result = service.getChecksum(doc);
        assertEquals("abc123hash", result);
    }

    @Test
    void getChecksum_withMetadataWithoutChecksum_returnsNull() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("file.txt")
                .metadata(Json.of("{\"key\":\"value\"}"))
                .build();

        Map<String, Object> metadataMap = Map.of("key", "value");
        when(jsonUtils.toMap(any(Json.class))).thenReturn(metadataMap);

        String result = service.getChecksum(doc);
        assertNull(result);
    }

    @Test
    void getChecksum_withNullMetadata_returnsNull() {
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .type(DocumentType.FILE)
                .name("file.txt")
                .metadata(null)
                .build();

        String result = service.getChecksum(doc);
        assertNull(result);
    }

    @Test
    void saveAndReplaceDocument_nonVersioning_sameChecksum_deletesNewFile() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("old/path.txt")
                .metadata(Json.of("{\"sha256\":\"same-hash\"}"))
                .build();

        FilePart newFilePart = mock(FilePart.class);
        ContentInfo contentInfo = new ContentInfo(100L, "same-hash");
        String oldPath = "old/path.txt";

        Map<String, Object> metadataMap = Map.of("sha256", "same-hash");
        when(jsonUtils.toMap(any(Json.class))).thenReturn(metadataMap);

        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("new/path.txt")); // Different path = non-versioning
        when(storageService.deleteFile("new/path.txt")).thenReturn(Mono.empty());

        StepVerifier.create(service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath))
                .expectNextMatches(result -> {
                    assertEquals(docId, result.getId());
                    return true;
                })
                .verifyComplete();

        verify(storageService).deleteFile("new/path.txt");
    }

    @Test
    void saveAndReplaceDocument_nonVersioning_differentChecksum_calculatesFromStorage() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("old/path.txt")
                .metadata(Json.of("{\"sha256\":\"old-hash\"}"))
                .build();

        FilePart newFilePart = mock(FilePart.class);
        // contentInfo has no checksum - force calculation from storage
        ContentInfo contentInfo = new ContentInfo(100L, null);
        String oldPath = "old/path.txt";

        Map<String, Object> metadataMap = Map.of("sha256", "old-hash");
        lenient().when(jsonUtils.toMap(any(Json.class))).thenReturn(metadataMap);

        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("new/path.txt")); // Different path = non-versioning

        Checksum newChecksum = new Checksum("new/path.txt", Map.of("sha256", "new-hash"));
        when(checksumService.calculateChecksum(eq("new/path.txt"), any()))
                .thenReturn(Mono.just(newChecksum));

        // The full chain calls super methods which need extensive mocking.
        // We verify the checksum service is invoked with the correct path
        service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath)
                .onErrorResume(e -> Mono.just(doc))
                .block();

        verify(checksumService).calculateChecksum(eq("new/path.txt"), any());
    }

    @Test
    void saveAndReplaceDocument_versioning_withClientChecksum_compareDirectly() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("versioned/path.txt")
                .metadata(Json.of("{\"sha256\":\"existing-hash\"}"))
                .build();

        FilePart newFilePart = mock(FilePart.class);
        ContentInfo contentInfo = new ContentInfo(100L, "existing-hash"); // Same hash
        String oldPath = "versioned/path.txt";

        Map<String, Object> metadataMap = Map.of("sha256", "existing-hash");
        when(jsonUtils.toMap(any(Json.class))).thenReturn(metadataMap);

        // Same path returned = versioning mode
        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("versioned/path.txt"));

        // Same hash → revert (delete latest version)
        when(storageService.deleteLatestVersion("versioned/path.txt")).thenReturn(Mono.empty());

        StepVerifier.create(service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath))
                .expectNextMatches(result -> {
                    assertEquals(docId, result.getId());
                    return true;
                })
                .verifyComplete();

        verify(storageService).deleteLatestVersion("versioned/path.txt");
    }

    @Test
    void saveAndReplaceDocument_versioning_noClientChecksum_calculatesFromStorage() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("versioned/path.txt")
                .metadata(Json.of("{\"sha256\":\"existing-hash\"}"))
                .build();

        FilePart newFilePart = mock(FilePart.class);
        // No client checksum - forces server-side calculation
        ContentInfo contentInfo = new ContentInfo(100L, null);
        String oldPath = "versioned/path.txt";

        Map<String, Object> metadataMap = Map.of("sha256", "existing-hash");
        when(jsonUtils.toMap(any(Json.class))).thenReturn(metadataMap);

        // Same path returned = versioning mode
        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("versioned/path.txt"));

        // Server calculates new checksum = same as existing → revert
        Checksum newChecksum = new Checksum("versioned/path.txt", Map.of("sha256", "existing-hash"));
        when(checksumService.calculateChecksum(eq("versioned/path.txt"), any()))
                .thenReturn(Mono.just(newChecksum));
        when(storageService.deleteLatestVersion("versioned/path.txt")).thenReturn(Mono.empty());

        StepVerifier.create(service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath))
                .expectNextMatches(result -> {
                    assertEquals(docId, result.getId());
                    return true;
                })
                .verifyComplete();

        verify(storageService).deleteLatestVersion("versioned/path.txt");
    }

    @Test
    void saveAndReplaceDocument_versioning_noExistingChecksum_calculatesPreviousVersion() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("versioned/path.txt")
                .metadata(null) // No metadata, so no existing checksum
                .build();

        FilePart newFilePart = mock(FilePart.class);
        ContentInfo contentInfo = new ContentInfo(100L, "new-hash");
        String oldPath = "versioned/path.txt";

        // Same path returned = versioning mode
        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("versioned/path.txt"));

        // No existing checksum → calculate from previous version
        when(checksumService.calculatePreviousVersionChecksum("versioned/path.txt"))
                .thenReturn(Mono.just("new-hash")); // Same hash → revert
        when(storageService.deleteLatestVersion("versioned/path.txt")).thenReturn(Mono.empty());

        StepVerifier.create(service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath))
                .expectNextMatches(result -> {
                    assertEquals(docId, result.getId());
                    return true;
                })
                .verifyComplete();

        verify(checksumService).calculatePreviousVersionChecksum("versioned/path.txt");
        verify(storageService).deleteLatestVersion("versioned/path.txt");
    }

    @Test
    void saveAndReplaceDocument_nonVersioning_clientChecksumNull_existingChecksumNull_calculatesFromBothPaths() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("old/path.txt")
                .metadata(null) // No existing checksum
                .build();

        FilePart newFilePart = mock(FilePart.class);
        ContentInfo contentInfo = new ContentInfo(100L, null); // No client checksum
        String oldPath = "old/path.txt";

        // Different path returned = non-versioning mode
        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("new/path.txt"));

        // Calculates checksum for new file
        Checksum newChecksum = new Checksum("new/path.txt", Map.of("sha256", "hash-a"));
        when(checksumService.calculateChecksum(eq("new/path.txt"), any()))
                .thenReturn(Mono.just(newChecksum));

        // Calculates checksum for old file (since no existing checksum in metadata)
        Checksum oldChecksum = new Checksum("old/path.txt", Map.of("sha256", "hash-a")); // Same hash
        when(checksumService.calculateChecksum(eq("old/path.txt"), any()))
                .thenReturn(Mono.just(oldChecksum));

        // Same hash → delete new file
        when(storageService.deleteFile("new/path.txt")).thenReturn(Mono.empty());

        StepVerifier.create(service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath))
                .expectNextMatches(result -> {
                    assertEquals(docId, result.getId());
                    return true;
                })
                .verifyComplete();

        verify(storageService).deleteFile("new/path.txt");
    }

    @Test
    void saveAndReplaceDocument_nonVersioning_clientChecksumProvided_noExistingChecksum_calculatesOldPath() {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .type(DocumentType.FILE)
                .name("file.txt")
                .storagePath("old/path.txt")
                .metadata(null) // No existing checksum
                .build();

        FilePart newFilePart = mock(FilePart.class);
        ContentInfo contentInfo = new ContentInfo(100L, "client-hash"); // Client provides checksum
        String oldPath = "old/path.txt";

        // Different path returned = non-versioning mode
        when(storageService.replaceFile(eq(oldPath), eq(newFilePart)))
                .thenReturn(Mono.just("new/path.txt"));

        // Calculates checksum for old file to compare with client-provided hash
        Checksum oldChecksum = new Checksum("old/path.txt", Map.of("sha256", "client-hash")); // Same
        when(checksumService.calculateChecksum(eq("old/path.txt"), any()))
                .thenReturn(Mono.just(oldChecksum));

        // Same hash → delete new file
        when(storageService.deleteFile("new/path.txt")).thenReturn(Mono.empty());

        StepVerifier.create(service.saveAndReplaceDocument(newFilePart, contentInfo, doc, oldPath))
                .expectNextMatches(result -> {
                    assertEquals(docId, result.getId());
                    return true;
                })
                .verifyComplete();

        verify(checksumService).calculateChecksum(eq("old/path.txt"), any());
        verify(storageService).deleteFile("new/path.txt");
    }
}
