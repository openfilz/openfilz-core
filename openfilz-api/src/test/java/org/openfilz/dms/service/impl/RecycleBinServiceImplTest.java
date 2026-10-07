package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.impl.DocumentSoftDeleteDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

/**
 * The recycle bin acts only on documents that are soft-deleted AND that the caller may see in the bin
 * ({@link RecycleBinServiceImpl#mayRestoreOrPurge(Document)}); an active document or an invisible one
 * answers 404 and nothing is written. A folder purge touches only its soft-deleted descendants.
 */
@ExtendWith(MockitoExtension.class)
class RecycleBinServiceImplTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentDAO documentDAO;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private DocumentSoftDeleteDAO documentSoftDeleteDAO;
    @Mock private StorageService storageService;
    @Mock private AuditService auditService;
    @Mock private TransactionalOperator tx;
    @Mock private StorageQuotaService storageQuotaService;

    private RecycleBinServiceImpl service;

    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new RecycleBinServiceImpl(documentRepository, documentDAO, metadataPostProcessor,
                documentSoftDeleteDAO, storageService, auditService, tx, storageQuotaService);
        // Transactions and audit are pass-through; only the tests that get that far use them.
        lenient().when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auditService.logAction(any(AuditAction.class), any(), any(UUID.class))).thenReturn(Mono.empty());
    }

    private static Document document(UUID id, DocumentType type, boolean active, String storagePath) {
        return Document.builder().id(id).name("doc-" + id).type(type).active(active).storagePath(storagePath).build();
    }

    // ==================== an active document is never the bin's to act on ====================

    @Test
    void restoreItems_activeDocument_isRefused_withoutAnyWrite() {
        when(documentRepository.findById(docId)).thenReturn(Mono.just(document(docId, FILE, true, "p")));

        StepVerifier.create(service.restoreItems(List.of(docId)))
                .expectError(DocumentNotFoundException.class)
                .verify();

        verify(documentSoftDeleteDAO, never()).isVisibleInRecycleBin(any());
        verify(documentSoftDeleteDAO, never()).restore(any());
        verify(documentSoftDeleteDAO, never()).restoreRecursive(any());
        verify(documentSoftDeleteDAO, never()).getTotalSizeToRestore(any());
        verifyNoInteractions(storageQuotaService, auditService);
    }

    @Test
    void permanentlyDeleteItems_activeDocument_isRefused_withoutAnyWrite() {
        when(documentRepository.findById(docId)).thenReturn(Mono.just(document(docId, FILE, true, "p")));

        StepVerifier.create(service.permanentlyDeleteItems(List.of(docId)))
                .expectError(DocumentNotFoundException.class)
                .verify();

        verify(documentSoftDeleteDAO, never()).permanentDelete(any());
        verifyNoInteractions(storageService, auditService);
    }

    @Test
    void permanentlyDeleteItems_unknownId_is404() {
        when(documentRepository.findById(docId)).thenReturn(Mono.empty());

        StepVerifier.create(service.permanentlyDeleteItems(List.of(docId)))
                .expectError(DocumentNotFoundException.class)
                .verify();

        verifyNoInteractions(storageService, documentSoftDeleteDAO);
    }

    // ==================== not visible in the bin for this caller ====================

    @Test
    void permanentlyDeleteItems_deletedButNotVisibleInBin_isRefused() {
        when(documentRepository.findById(docId)).thenReturn(Mono.just(document(docId, FILE, false, "p")));
        when(documentSoftDeleteDAO.isVisibleInRecycleBin(docId)).thenReturn(Mono.just(false));

        StepVerifier.create(service.permanentlyDeleteItems(List.of(docId)))
                .expectError(DocumentNotFoundException.class)
                .verify();

        verify(documentSoftDeleteDAO, never()).permanentDelete(any());
        verifyNoInteractions(storageService, auditService);
    }

    @Test
    void restoreItems_hookOverriddenToRefuse_is404_andTheDaoDefaultIsNotConsulted() {
        RecycleBinServiceImpl tightened = new RecycleBinServiceImpl(documentRepository, documentDAO,
                metadataPostProcessor, documentSoftDeleteDAO, storageService, auditService, tx, storageQuotaService) {
            @Override
            protected Mono<Boolean> mayRestoreOrPurge(Document document) {
                return Mono.just(false);
            }
        };
        when(documentRepository.findById(docId)).thenReturn(Mono.just(document(docId, FILE, false, "p")));

        StepVerifier.create(tightened.restoreItems(List.of(docId)))
                .expectError(DocumentNotFoundException.class)
                .verify();

        verify(documentSoftDeleteDAO, never()).isVisibleInRecycleBin(any());
        verify(documentSoftDeleteDAO, never()).restore(any());
        verifyNoInteractions(storageQuotaService);
    }

    // ==================== soft-deleted and visible: proceeds ====================

    @Test
    void restoreItems_visibleDeletedFile_isRestored() {
        Document deleted = document(docId, FILE, false, "p");
        when(documentRepository.findById(docId)).thenReturn(Mono.just(deleted));
        when(documentSoftDeleteDAO.isVisibleInRecycleBin(docId)).thenReturn(Mono.just(true));
        when(documentSoftDeleteDAO.getTotalSizeToRestore(docId)).thenReturn(Mono.just(10L));
        when(storageQuotaService.checkStorage(10L)).thenReturn(Mono.empty());
        when(documentSoftDeleteDAO.restore(docId)).thenReturn(Mono.empty());

        StepVerifier.create(service.restoreItems(List.of(docId))).verifyComplete();

        verify(documentSoftDeleteDAO).restore(docId);
        verify(auditService).logAction(AuditAction.RESTORE_FILE, FILE, docId);
        verify(metadataPostProcessor).updateIndexField(eq(deleted), anyString(), eq(true));
    }

    @Test
    void permanentlyDeleteItems_visibleDeletedFile_isPurgedWithItsStorage() {
        when(documentRepository.findById(docId)).thenReturn(Mono.just(document(docId, FILE, false, "p")));
        when(documentSoftDeleteDAO.isVisibleInRecycleBin(docId)).thenReturn(Mono.just(true));
        when(storageService.deleteFile("p")).thenReturn(Mono.empty());
        when(documentSoftDeleteDAO.permanentDelete(docId)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.permanentlyDeleteItems(List.of(docId))).verifyComplete();

        verify(storageService).deleteFile("p");
        verify(documentSoftDeleteDAO).permanentDelete(docId);
        verify(auditService).logAction(AuditAction.PERMANENT_DELETE_FILE, FILE, docId);
        verify(metadataPostProcessor).deleteDocument(docId);
    }

    @Test
    void permanentlyDeleteItems_folder_purgesOnlyItsSoftDeletedDescendants() {
        UUID deletedChildId = UUID.randomUUID();
        UUID activeChildId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Mono.just(document(docId, FOLDER, false, null)));
        when(documentSoftDeleteDAO.isVisibleInRecycleBin(docId)).thenReturn(Mono.just(true));
        when(documentRepository.findByParentId(docId)).thenReturn(Flux.just(
                document(deletedChildId, FILE, false, "deleted-child"),
                document(activeChildId, FILE, true, "active-child")));
        when(storageService.deleteFile("deleted-child")).thenReturn(Mono.empty());
        when(documentSoftDeleteDAO.permanentDelete(deletedChildId)).thenReturn(Mono.just(1L));
        when(documentSoftDeleteDAO.permanentDelete(docId)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.permanentlyDeleteItems(List.of(docId))).verifyComplete();

        verify(storageService).deleteFile("deleted-child");
        verify(storageService, never()).deleteFile("active-child");
        verify(documentSoftDeleteDAO).permanentDelete(deletedChildId);
        verify(documentSoftDeleteDAO, never()).permanentDelete(activeChildId);
        verify(documentSoftDeleteDAO).permanentDelete(docId);
        verify(auditService).logAction(AuditAction.PERMANENT_DELETE_FOLDER, FOLDER, docId);
        // Only the top-level id goes through the bin visibility check; descendants follow their folder
        verify(documentSoftDeleteDAO, never()).isVisibleInRecycleBin(deletedChildId);
    }
}
