package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.config.AuditProperties;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.audit.MoveAudit;
import org.openfilz.dms.dto.audit.UpdateMetadataAudit;
import org.openfilz.dms.dto.audit.WorkflowAuditCause;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.AuditDAO;
import org.openfilz.dms.service.AuditChainService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A workflow that moves a document or stamps metadata must leave a trail that says so.
 * <p>
 * The actor stays the person the workflow acted for — that part is the {@code SideEffects}
 * Authentication and is not what this pins. This pins the other half: the entry that action writes
 * carries the workflow, the instance and the status, so it cannot be read as a manual move.
 */
class AuditWorkflowCauseTest {

    private static final UUID DOCUMENT = UUID.fromString("9c3f1a2b-1111-4c3d-8e5f-0a1b2c3d4e5f");
    private static final UUID INSTANCE = UUID.fromString("7a2e5c11-2222-4b8a-9d0e-1f2a3b4c5d6e");
    private static final UUID FOLDER = UUID.fromString("5d4c3b2a-3333-4a9b-8c7d-6e5f4a3b2c1d");

    private final AuditDAO auditDAO = mock(AuditDAO.class);
    private final AuditServiceImpl service = new AuditServiceImpl(auditDAO, new AuditProperties(),
            new AuditChainProperties(), mock(AuditChainService.class));

    private IAuditLogDetails logged(Mono<Void> call) {
        when(auditDAO.logAction(any(), any(), any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(call).verifyComplete();
        ArgumentCaptor<IAuditLogDetails> captor = ArgumentCaptor.forClass(IAuditLogDetails.class);
        verify(auditDAO).logAction(any(), any(), eq(DOCUMENT), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a move made by a workflow says which workflow, instance and status")
    void aWorkflowMoveIsMarkedAsSuch() {
        MoveAudit details = new MoveAudit(FOLDER);

        IAuditLogDetails written = logged(service.logAction(AuditAction.MOVE_FILE, DocumentType.FILE, DOCUMENT, details)
                .contextWrite(ctx -> ctx.put(WorkflowAuditCause.CONTEXT_KEY,
                        new WorkflowAuditCause(INSTANCE, "Two-step approval", "Approved"))));

        assertThat(written).isInstanceOf(AuditLogDetails.class);
        AuditLogDetails d = (AuditLogDetails) written;
        assertThat(d.getWorkflowInstanceId()).isEqualTo(INSTANCE);
        assertThat(d.getWorkflow()).isEqualTo("Two-step approval");
        assertThat(d.getWorkflowState()).isEqualTo("Approved");
        // the action's own payload survives untouched
        assertThat(((MoveAudit) d).getTargetFolderId()).isEqualTo(FOLDER);
    }

    @Test
    @DisplayName("metadata stamped by a workflow is marked the same way")
    void aWorkflowMetadataUpdateIsMarkedAsSuch() {
        IAuditLogDetails written = logged(service.logAction(AuditAction.UPDATE_DOCUMENT_METADATA, DocumentType.FILE, DOCUMENT,
                        new UpdateMetadataAudit(Map.of("status", "approved")))
                .contextWrite(ctx -> ctx.put(WorkflowAuditCause.CONTEXT_KEY,
                        new WorkflowAuditCause(INSTANCE, "Simple approval", "Approved"))));

        assertThat(((AuditLogDetails) written).getWorkflow()).isEqualTo("Simple approval");
    }

    @Test
    @DisplayName("the same action done by hand carries nothing — no workflow noise on manual work")
    void aManualActionIsNotMarked() {
        IAuditLogDetails written = logged(service.logAction(AuditAction.MOVE_FILE, DocumentType.FILE, DOCUMENT, new MoveAudit(FOLDER)));

        AuditLogDetails d = (AuditLogDetails) written;
        assertThat(d.getWorkflowInstanceId()).isNull();
        assertThat(d.getWorkflow()).isNull();
        assertThat(d.getWorkflowState()).isNull();
    }
}
