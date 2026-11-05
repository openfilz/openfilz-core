package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openfilz.dms.controller.rest.OnlyOfficeController;
import org.openfilz.dms.dto.audit.EditorConflictAudit;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.service.OnlyOfficeService;
import org.openfilz.dms.service.onlyoffice.EditorConflictNotifier;
import org.openfilz.dms.service.onlyoffice.EditorSessionStore;
import org.openfilz.dms.service.onlyoffice.EditorSessionStore.EditorSession;
import org.openfilz.dms.utils.ContentInfo;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The side of the conflict copy a running server cannot be made to show: a refused copy, and the name. */
class AbstractOnlyOfficeServiceConflictCopyTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final AuditService auditService = mock(AuditService.class);

    private AbstractOnlyOfficeService<OnlyOfficeUserInfo> service() {
        AbstractOnlyOfficeService<OnlyOfficeUserInfo> service = new AbstractOnlyOfficeService<>(
                null, null, null, null, null, documentService, null);
        service.setEditorSessionCollaborators(mock(EditorSessionStore.class), mock(DocumentVersionService.class),
                auditService, new EditorConflictNotifier() {
                });
        return service;
    }

    @Test
    @DisplayName("a refused copy (WORM, folder) is audited, and the save fails: the document server keeps the work")
    void refusedCopyIsAuditedAndTheSaveFails() {
        Document current = Document.builder().id(UUID.randomUUID()).name("report.docx").type(DocumentType.FILE).build();
        when(documentService.uploadDocument(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new OperationForbiddenException("Forbidden in WORM mode")));
        when(auditService.logAction(any(), any(), any(), any(IAuditLogDetails.class))).thenReturn(Mono.empty());

        Mono<Void> saved = ReflectionTestUtils.invokeMethod(service(), "saveConflictCopy", current,
                new EditorSession("key-1", current.getId(), "path:a", null), Path.of("ignored"),
                new ContentInfo(5L, null), callback());

        // Not the refusal itself: the callback endpoint answers 403 on a forbidden operation
        StepVerifier.create(saved).verifyErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(AbstractOnlyOfficeService.ConflictCopyRefusedException.class)
                .hasCauseInstanceOf(OperationForbiddenException.class)
                .hasMessageContaining("Forbidden in WORM mode"));
        ArgumentCaptor<IAuditLogDetails> details = ArgumentCaptor.forClass(IAuditLogDetails.class);
        verify(auditService).logAction(eq(AuditAction.ONLYOFFICE_SAVE_CONFLICT), eq(DocumentType.FILE), eq(current.getId()), details.capture());
        EditorConflictAudit audit = (EditorConflictAudit) details.getValue();
        assertThat(audit.getCopyId()).isNull();
        assertThat(audit.getSessionKey()).isEqualTo("key-1");
        assertThat(audit.getFailure()).isEqualTo("Forbidden in WORM mode");
    }

    @Test
    @DisplayName("a refused copy is still an error when its audit fails")
    void refusedCopyFailsEvenWhenTheAuditDoes() {
        Document current = Document.builder().id(UUID.randomUUID()).name("report.docx").type(DocumentType.FILE).build();
        when(documentService.uploadDocument(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new OperationForbiddenException("Forbidden in WORM mode")));
        when(auditService.logAction(any(), any(), any(), any(IAuditLogDetails.class)))
                .thenReturn(Mono.error(new IllegalStateException("audit down")));

        Mono<Void> saved = ReflectionTestUtils.invokeMethod(service(), "saveConflictCopy", current,
                new EditorSession("key-1", current.getId(), "path:a", null), Path.of("ignored"),
                new ContentInfo(5L, null), callback());

        StepVerifier.create(saved).verifyError(AbstractOnlyOfficeService.ConflictCopyRefusedException.class);
    }

    @Test
    @DisplayName("the callback answers error 1 on a refused copy, not 403 and not saved")
    void theCallbackAnswersAnErrorOnARefusedCopy() {
        UUID documentId = UUID.randomUUID();
        OnlyOfficeService onlyOffice = mock(OnlyOfficeService.class);
        when(onlyOffice.handleCallback(documentId, callback())).thenReturn(Mono.error(
                new AbstractOnlyOfficeService.ConflictCopyRefusedException(new OperationForbiddenException("Forbidden in WORM mode"))));

        StepVerifier.create(new OnlyOfficeController(onlyOffice, auditService).handleCallback(documentId, callback()))
                .expectNext(Map.of("error", 1))
                .verifyComplete();
    }

    @Test
    @DisplayName("a failure that may pass (the database, the network) propagates, so the document server retries")
    void otherFailuresPropagate() {
        Document current = Document.builder().id(UUID.randomUUID()).name("report.docx").type(DocumentType.FILE).build();
        when(documentService.uploadDocument(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("connection lost")));

        Mono<Void> saved = ReflectionTestUtils.invokeMethod(service(), "saveConflictCopy", current,
                new EditorSession("key-1", current.getId(), "path:a", null), Path.of("ignored"),
                new ContentInfo(5L, null), callback());

        StepVerifier.create(saved).verifyError(IllegalStateException.class);
    }

    @Test
    @DisplayName("a storage failure is not a refusal: it propagates, and the document server keeps the work")
    void aStorageFailureIsNotARefusal() {
        Document current = Document.builder().id(UUID.randomUUID()).name("report.docx").type(DocumentType.FILE).build();
        when(documentService.uploadDocument(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new org.openfilz.dms.exception.StorageException(new java.io.IOException("storage unreachable"))));

        Mono<Void> saved = ReflectionTestUtils.invokeMethod(service(), "saveConflictCopy", current,
                new EditorSession("key-1", current.getId(), "path:a", null), Path.of("ignored"),
                new ContentInfo(5L, null), callback());

        StepVerifier.create(saved).verifyError(org.openfilz.dms.exception.StorageException.class);
        verify(auditService, org.mockito.Mockito.never()).logAction(any(), any(), any(), any(IAuditLogDetails.class));
    }

    @Test
    @DisplayName("the copy's name keeps the extension, and carries no character a file system refuses")
    void copyName() {
        OffsetDateTime when = OffsetDateTime.of(2026, 11, 5, 23, 19, 3, 0, ZoneOffset.UTC);

        assertThat(AbstractOnlyOfficeService.conflictCopyName("report.final.docx", when))
                .isEqualTo("report.final (editor copy 2026-11-05 23-19-03).docx");
        assertThat(AbstractOnlyOfficeService.conflictCopyName("noextension", when))
                .isEqualTo("noextension (editor copy 2026-11-05 23-19-03)");
        assertThat(AbstractOnlyOfficeService.conflictCopyName("x".repeat(400) + ".docx", when))
                .hasSizeLessThan(255).endsWith("(editor copy 2026-11-05 23-19-03).docx");
    }

    private static OnlyOfficeCallbackRequest callback() {
        return new OnlyOfficeCallbackRequest(OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "key-1", "http://ds/o.docx",
                List.of("user"), null, null, null, null, null);
    }
}
