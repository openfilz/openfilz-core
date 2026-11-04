package org.openfilz.dms.controller.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.OnlyOfficeService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Opening a document in OnlyOffice is audited once, when the user asks for the editor config.
 */
@ExtendWith(MockitoExtension.class)
class OnlyOfficeControllerOpenAuditTest {

    @Mock
    private OnlyOfficeService onlyOfficeService;
    @Mock
    private AuditService auditService;
    @Mock
    private OnlyOfficeConfigResponse config;

    private OnlyOfficeController controller;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new OnlyOfficeController(onlyOfficeService, auditService);
    }

    @Test
    void latestContent_auditsOneOpen() {
        when(onlyOfficeService.generateEditorConfig(documentId, true, null, "fr")).thenReturn(Mono.just(config));
        when(auditService.logAction(AuditAction.OPEN_DOCUMENT, DocumentType.FILE, documentId)).thenReturn(Mono.empty());

        StepVerifier.create(controller.getEditorConfig(documentId, true, null, "fr"))
                .expectNext(config)
                .verifyComplete();
        verify(auditService).logAction(AuditAction.OPEN_DOCUMENT, DocumentType.FILE, documentId);
    }

    @Test
    void storedVersion_isNotAudited() {
        when(onlyOfficeService.generateEditorConfig(documentId, false, "v1", null)).thenReturn(Mono.just(config));

        StepVerifier.create(controller.getEditorConfig(documentId, false, "v1", null))
                .expectNext(config)
                .verifyComplete();
        verifyNoInteractions(auditService);
    }

    @Test
    void refusedConfig_isNotAudited() {
        when(onlyOfficeService.generateEditorConfig(documentId, true, null, null))
                .thenReturn(Mono.error(new IllegalArgumentException("Document not found")));

        StepVerifier.create(controller.getEditorConfig(documentId, true, null, null))
                .verifyError(IllegalArgumentException.class);
        verifyNoInteractions(auditService);
    }
}
