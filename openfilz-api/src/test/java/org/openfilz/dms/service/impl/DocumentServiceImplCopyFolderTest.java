package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.request.CopyRequest;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentDeleteService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.SaveDocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.openfilz.dms.utils.BlankDocumentGenerator;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.openfilz.dms.enums.DocumentType.FOLDER;

/**
 * {@link DocumentServiceImpl#copyFolders(CopyRequest)} must refuse to copy a folder into itself or
 * into one of its own descendants before anything is written: the copy would otherwise show up among
 * the source's children and be copied again, forever, filling storage and the database.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplCopyFolderTest {

    @Mock private TransactionalOperator tx;
    @Mock private StorageService storageService;
    @Mock private ObjectMapper objectMapper;
    @Mock private AuditService auditService;
    @Mock private JsonUtils jsonUtils;
    @Mock private DocumentDAO documentDAO;
    @Mock private SaveDocumentService saveDocumentService;
    @Mock private MetadataPostProcessor metadataPostProcessor;
    @Mock private DocumentDeleteService documentDeleteService;
    @Mock private BlankDocumentGenerator blankDocumentGenerator;
    @Mock private StorageQuotaService storageQuotaService;

    private DocumentServiceImpl service;

    private final UUID sourceId = UUID.randomUUID();
    private final UUID midId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(tx, storageService, objectMapper, auditService, jsonUtils, documentDAO,
                saveDocumentService, metadataPostProcessor, documentDeleteService, blankDocumentGenerator, storageQuotaService);
    }

    private static Document folder(UUID id, UUID parentId) {
        return Document.builder().id(id).name("folder-" + id).type(FOLDER).parentId(parentId).build();
    }

    @Test
    void copyFolders_intoItself_isForbidden_withoutAnyWrite() {
        when(documentDAO.findById(eq(sourceId), any())).thenReturn(Mono.just(folder(sourceId, null)));

        StepVerifier.create(service.copyFolders(new CopyRequest(List.of(sourceId), sourceId, false)))
                .expectError(OperationForbiddenException.class)
                .verify();

        assertNoWrite();
    }

    @Test
    void copyFolders_intoDirectChild_isForbidden_withoutAnyWrite() {
        // target is a child of the source folder
        when(documentDAO.findById(eq(targetId), any())).thenReturn(Mono.just(folder(targetId, sourceId)));

        StepVerifier.create(service.copyFolders(new CopyRequest(List.of(sourceId), targetId, false)))
                .expectError(OperationForbiddenException.class)
                .verify();

        assertNoWrite();
    }

    @Test
    void copyFolders_intoDeepDescendant_isForbidden_withoutAnyWrite() {
        // source > mid > target
        when(documentDAO.findById(eq(targetId), any())).thenReturn(Mono.just(folder(targetId, midId)));
        when(documentDAO.findById(eq(midId), any())).thenReturn(Mono.just(folder(midId, sourceId)));

        StepVerifier.create(service.copyFolders(new CopyRequest(List.of(sourceId), targetId, true)))
                .expectError(OperationForbiddenException.class)
                .verify();

        assertNoWrite();
    }

    @Test
    void copyFolders_intoUnrelatedFolder_passesTheGuard() {
        // target sits at the root: not a descendant, so the copy itself starts (and fails here only
        // because the mocked DAO does not know the source folder — i.e. the guard let it through)
        when(documentDAO.findById(eq(targetId), any())).thenReturn(Mono.just(folder(targetId, null)));
        when(documentDAO.findById(eq(sourceId), eq(AccessType.RO))).thenReturn(Mono.empty());

        StepVerifier.create(service.copyFolders(new CopyRequest(List.of(sourceId), targetId, false)))
                .expectError(DocumentNotFoundException.class)
                .verify();

        verify(documentDAO).findById(sourceId, AccessType.RO);
        assertNoWrite();
    }

    private void assertNoWrite() {
        verifyNoInteractions(saveDocumentService, storageService, auditService);
        verify(documentDAO, never()).findDocumentsByParentId(any());
        verify(documentDAO, never()).findDocumentsByParentIdAndType(any(), any());
    }
}
