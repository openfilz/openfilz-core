package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.InstantiateTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateField;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.entity.SignatureTemplate;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.repository.SignatureTemplateRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.utils.SignatureJson;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignatureTemplateServiceImplTest {

    @Mock SignatureTemplateRepository repo;
    @Mock SignatureService signatureService;
    @Mock AuditService auditService;

    private SignatureTemplateServiceImpl service;

    private static final String OWNER = "Owner@Example.com";
    private static final SignatureTemplateRole CLIENT = new SignatureTemplateRole("Client", 1, SignatureRecipientRole.SIGNER, SignatureAuthMethod.EMAIL_OTP);
    private static final SignatureTemplateRole SALES = new SignatureTemplateRole("Sales", 0, null, null);
    private static final SignatureTemplateField CLIENT_SIG = new SignatureTemplateField("Client", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.1, true, "Sign here", null);
    private static final SignatureTemplateField CLIENT_DATE = new SignatureTemplateField("Client", SignatureFieldType.DATE_SIGNED, 0, 0.5, 0.1, 0.2, 0.05, null, null, Map.of("format", "yyyy-MM-dd"));
    private static final SignatureTemplateField SALES_SIG = new SignatureTemplateField("Sales", SignatureFieldType.SIGNATURE, 1, 0.1, 0.8, 0.3, 0.1, true, null, null);

    @BeforeEach
    void setUp() {
        service = new SignatureTemplateServiceImpl(repo, signatureService, auditService);
    }

    private static SignatureTemplateRequest request(UUID sourceDocId, List<SignatureTemplateRole> roles, List<SignatureTemplateField> fields) {
        return new SignatureTemplateRequest("NDA", "desc", sourceDocId, roles, fields, "please sign", 15, true);
    }

    private static SignatureTemplate persisted(UUID sourceDocId) {
        return SignatureTemplate.builder()
                .id(UUID.randomUUID())
                .ownerEmail("owner@example.com")
                .name("NDA")
                .description("desc")
                .sourceDocId(sourceDocId)
                .roles(SignatureJson.toJson(List.of(CLIENT, SALES)))
                .fields(SignatureJson.toJson(List.of(CLIENT_SIG, CLIENT_DATE, SALES_SIG)))
                .message("template message")
                .expiresInDays(15)
                .sequential(true)
                .createdAt(OffsetDateTime.now().minusDays(1))
                .updatedAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    // ── create ────────────────────────────────────────────────────────────

    @Test
    void create_persistsRolesAndFieldsAsJson_lowercasesOwner_andAuditsWhenSourceDocPresent() {
        UUID doc = UUID.randomUUID();
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.logAction(AuditAction.SIGNATURE_TEMPLATE_CREATED, DocumentType.FILE, doc)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(doc, List.of(CLIENT, SALES), List.of(CLIENT_SIG, CLIENT_DATE, SALES_SIG)), OWNER))
                .assertNext(dto -> {
                    assertThat(dto.id()).isNotNull();
                    assertThat(dto.ownerEmail()).isEqualTo("owner@example.com");
                    assertThat(dto.name()).isEqualTo("NDA");
                    assertThat(dto.sourceDocId()).isEqualTo(doc);
                    assertThat(dto.roles()).containsExactly(CLIENT, SALES);
                    assertThat(dto.fields()).containsExactly(CLIENT_SIG, CLIENT_DATE, SALES_SIG);
                    assertThat(dto.sequential()).isTrue();
                    assertThat(dto.expiresInDays()).isEqualTo(15);
                    assertThat(dto.createdAt()).isNotNull();
                    assertThat(dto.updatedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<SignatureTemplate> captor = ArgumentCaptor.forClass(SignatureTemplate.class);
        verify(repo).save(captor.capture());
        SignatureTemplate saved = captor.getValue();
        assertThat(saved.isNew()).isTrue();
        assertThat(saved.getRoles().asString()).contains("\"name\":\"Client\"").contains("\"authMethod\":\"EMAIL_OTP\"");
        assertThat(saved.getFields().asString()).contains("\"type\":\"SIGNATURE\"").contains("\"format\":\"yyyy-MM-dd\"");
        verify(auditService).logAction(AuditAction.SIGNATURE_TEMPLATE_CREATED, DocumentType.FILE, doc);
    }

    @Test
    void create_withoutSourceDoc_doesNotAudit_andSequentialDefaultsFalse() {
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        SignatureTemplateRequest req = new SignatureTemplateRequest("T", null, null, List.of(CLIENT), List.of(CLIENT_SIG), null, null, null);

        StepVerifier.create(service.create(req, OWNER))
                .assertNext(dto -> {
                    assertThat(dto.sourceDocId()).isNull();
                    assertThat(dto.sequential()).isFalse();
                })
                .verifyComplete();
        verifyNoInteractions(auditService);
    }

    @Test
    void create_duplicateRole_422() {
        assertThatThrownBy(() -> service.create(request(null, List.of(CLIENT, CLIENT), List.of(CLIENT_SIG)), OWNER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("Duplicate role 'Client'");
                });
        verifyNoInteractions(repo);
    }

    @Test
    void create_fieldWithUnknownRole_422() {
        SignatureTemplateField orphan = new SignatureTemplateField("Ghost", SignatureFieldType.TEXT, 0, 0.1, 0.1, 0.1, 0.1, null, null, null);
        assertThatThrownBy(() -> service.create(request(null, List.of(CLIENT), List.of(orphan)), OWNER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("unknown role 'Ghost'");
                });
    }

    @Test
    void create_fieldOutsidePage_422() {
        SignatureTemplateField outside = new SignatureTemplateField("Client", SignatureFieldType.TEXT, 0, 0.9, 0.1, 0.2, 0.1, null, null, null);
        assertThatThrownBy(() -> service.create(request(null, List.of(CLIENT), List.of(outside)), OWNER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("within the page");
                });
        SignatureTemplateField zeroWidth = new SignatureTemplateField("Client", SignatureFieldType.TEXT, 0, 0.1, 0.1, 0.0, 0.1, null, null, null);
        assertThatThrownBy(() -> service.create(request(null, List.of(CLIENT), List.of(zeroWidth)), OWNER))
                .isInstanceOf(ResponseStatusException.class);
        SignatureTemplateField negative = new SignatureTemplateField("Client", SignatureFieldType.TEXT, 0, -0.1, 0.1, 0.1, 0.1, null, null, null);
        assertThatThrownBy(() -> service.create(request(null, List.of(CLIENT), List.of(negative)), OWNER))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── update / get / list / delete ──────────────────────────────────────

    @Test
    void update_ownedTemplate_appliesChanges() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        SignatureTemplateRequest req = new SignatureTemplateRequest("Renamed", null, null, List.of(SALES), List.of(SALES_SIG), null, 3, false);

        StepVerifier.create(service.update(t.getId(), req, "OWNER@example.com"))
                .assertNext(dto -> {
                    assertThat(dto.name()).isEqualTo("Renamed");
                    assertThat(dto.roles()).containsExactly(SALES);
                    assertThat(dto.fields()).containsExactly(SALES_SIG);
                    assertThat(dto.sequential()).isFalse();
                    assertThat(dto.expiresInDays()).isEqualTo(3);
                    assertThat(dto.createdAt()).isEqualTo(t.getCreatedAt());
                    assertThat(dto.updatedAt()).isAfter(t.getCreatedAt());
                })
                .verifyComplete();
    }

    @Test
    void update_invalidRequest_failsBeforeLookup() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), request(null, List.of(CLIENT, CLIENT), List.of()), OWNER))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void get_notFound_404() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(service.get(id, OWNER))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void get_foreignOwner_403() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.get(t.getId(), "intruder@example.com"))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ResponseStatusException) e).getReason()).contains("Not your template");
                })
                .verify();
    }

    @Test
    void get_owner_returnsDto() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.get(t.getId(), OWNER))
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(t.getId());
                    assertThat(dto.roles()).hasSize(2);
                    assertThat(dto.fields()).hasSize(3);
                })
                .verifyComplete();
    }

    @Test
    void list_lowercasesOwner() {
        when(repo.findByOwnerEmailOrderByUpdatedAtDesc("owner@example.com")).thenReturn(Flux.just(persisted(null), persisted(null)));
        StepVerifier.create(service.list(OWNER)).expectNextCount(2).verifyComplete();
    }

    @Test
    void delete_ownedWithSourceDoc_deletesAndAudits() {
        UUID doc = UUID.randomUUID();
        SignatureTemplate t = persisted(doc);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        when(repo.delete(t)).thenReturn(Mono.empty());
        when(auditService.logAction(AuditAction.SIGNATURE_TEMPLATE_DELETED, DocumentType.FILE, doc)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(t.getId(), OWNER)).verifyComplete();
        verify(repo).delete(t);
        verify(auditService).logAction(AuditAction.SIGNATURE_TEMPLATE_DELETED, DocumentType.FILE, doc);
    }

    @Test
    void delete_withoutSourceDoc_noAudit() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        when(repo.delete(t)).thenReturn(Mono.empty());
        StepVerifier.create(service.delete(t.getId(), OWNER)).verifyComplete();
        verifyNoInteractions(auditService);
    }

    @Test
    void delete_foreignOwner_403_andNothingDeleted() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.delete(t.getId(), "x@example.com"))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        verify(repo, never()).delete(any(SignatureTemplate.class));
    }

    // ── instantiate ───────────────────────────────────────────────────────

    private static InstantiateTemplateRequest.RoleBinding bind(String role, String email) {
        return new InstantiateTemplateRequest.RoleBinding(role, null, role + " person", email, null, null);
    }

    private static InstantiateTemplateRequest instantiate(UUID sourceDocId, String title, List<InstantiateTemplateRequest.RoleBinding> bindings) {
        return new InstantiateTemplateRequest(sourceDocId, title, null, bindings, null, 5, "fr", false);
    }

    private SignatureService.Actor actor() {
        return SignatureService.Actor.of(UUID.randomUUID().toString(), OWNER);
    }

    @Test
    void instantiate_happyPath_buildsCreateRequestFromTemplate() {
        UUID doc = UUID.randomUUID();
        SignatureTemplate t = persisted(doc);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        SignatureEnvelopeDTO created = new SignatureEnvelopeDTO(UUID.randomUUID(), "NDA", null, doc, null, null, "owner@example.com",
                true, 0, t.getId(), null, null, null, null, null, null, List.of());
        when(signatureService.create(any(), any())).thenReturn(Mono.just(created));
        SignatureService.Actor actor = actor();

        StepVerifier.create(service.instantiate(t.getId(), instantiate(null, "  ",
                        List.of(bind("Sales", "sales@example.com"), bind("Client", "client@example.com"))), actor))
                .expectNext(created)
                .verifyComplete();

        ArgumentCaptor<CreateSignatureEnvelopeRequest> captor = ArgumentCaptor.forClass(CreateSignatureEnvelopeRequest.class);
        verify(signatureService).create(captor.capture(), eq(actor));
        CreateSignatureEnvelopeRequest req = captor.getValue();
        assertThat(req.sourceDocId()).isEqualTo(doc);
        assertThat(req.title()).isEqualTo("NDA");                 // blank title → template name
        assertThat(req.message()).isEqualTo("template message");  // null message → template message
        assertThat(req.expiresInDays()).isEqualTo(15);            // null → template value
        assertThat(req.sequential()).isTrue();
        assertThat(req.reminderDays()).isEqualTo(5);
        assertThat(req.locale()).isEqualTo("fr");
        assertThat(req.send()).isFalse();
        assertThat(req.templateId()).isEqualTo(t.getId());
        assertThat(req.recipients()).hasSize(2);

        SignatureRecipientInput sales = req.recipients().get(0);
        assertThat(sales.email()).isEqualTo("sales@example.com");
        assertThat(sales.name()).isEqualTo("Sales person");
        assertThat(sales.orderIndex()).isEqualTo(0);
        assertThat(sales.role()).isNull();
        assertThat(sales.effectiveRole()).isEqualTo(SignatureRecipientRole.SIGNER);
        assertThat(sales.authMethod()).isNull();
        assertThat(sales.fields()).hasSize(1);
        assertThat(sales.fields().getFirst().type()).isEqualTo(SignatureFieldType.SIGNATURE);
        assertThat(sales.fields().getFirst().page()).isEqualTo(1);
        assertThat(sales.field()).isNull();

        SignatureRecipientInput client = req.recipients().get(1);
        assertThat(client.email()).isEqualTo("client@example.com");
        assertThat(client.orderIndex()).isEqualTo(1);
        assertThat(client.role()).isEqualTo(SignatureRecipientRole.SIGNER);
        assertThat(client.authMethod()).isEqualTo(SignatureAuthMethod.EMAIL_OTP);
        assertThat(client.fields()).extracting(f -> f.type()).containsExactly(SignatureFieldType.SIGNATURE, SignatureFieldType.DATE_SIGNED);
        assertThat(client.fields().get(0).label()).isEqualTo("Sign here");
        assertThat(client.fields().get(1).options()).containsEntry("format", "yyyy-MM-dd");
    }

    @Test
    void instantiate_requestOverridesWin() {
        UUID templateDoc = UUID.randomUUID();
        UUID overrideDoc = UUID.randomUUID();
        SignatureTemplate t = persisted(templateDoc);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        when(signatureService.create(any(), any())).thenReturn(Mono.just(
                new SignatureEnvelopeDTO(UUID.randomUUID(), "x", null, overrideDoc, null, null, null, false, 0, null, null, null, null, null, null, null, List.of())));
        InstantiateTemplateRequest req = new InstantiateTemplateRequest(overrideDoc, "Custom title", "custom msg",
                List.of(bind("Sales", "s@x.io"), bind("Client", "c@x.io")), 42, null, null, null);

        StepVerifier.create(service.instantiate(t.getId(), req, actor())).expectNextCount(1).verifyComplete();

        ArgumentCaptor<CreateSignatureEnvelopeRequest> captor = ArgumentCaptor.forClass(CreateSignatureEnvelopeRequest.class);
        verify(signatureService).create(captor.capture(), any());
        assertThat(captor.getValue().sourceDocId()).isEqualTo(overrideDoc);
        assertThat(captor.getValue().title()).isEqualTo("Custom title");
        assertThat(captor.getValue().message()).isEqualTo("custom msg");
        assertThat(captor.getValue().expiresInDays()).isEqualTo(42);
        assertThat(captor.getValue().send()).isNull();
    }

    @Test
    void instantiate_noDocumentAnywhere_422() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.instantiate(t.getId(), instantiate(null, null, List.of(bind("Sales", "s@x.io"), bind("Client", "c@x.io"))), actor()))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((ResponseStatusException) e).getReason()).contains("no default document");
                })
                .verify();
        verifyNoInteractions(signatureService);
    }

    @Test
    void instantiate_unknownRole_422() {
        SignatureTemplate t = persisted(UUID.randomUUID());
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.instantiate(t.getId(), instantiate(null, null, List.of(bind("Nobody", "n@x.io"))), actor()))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((ResponseStatusException) e).getReason()).contains("Unknown template role 'Nobody'");
                })
                .verify();
        verifyNoInteractions(signatureService);
    }

    @Test
    void instantiate_duplicateBinding_422() {
        SignatureTemplate t = persisted(UUID.randomUUID());
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.instantiate(t.getId(), instantiate(null, null,
                        List.of(bind("Client", "a@x.io"), bind("Client", "b@x.io"), bind("Sales", "s@x.io"))), actor()))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((ResponseStatusException) e).getReason()).contains("bound twice");
                })
                .verify();
    }

    @Test
    void instantiate_unboundRole_422() {
        SignatureTemplate t = persisted(UUID.randomUUID());
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.instantiate(t.getId(), instantiate(null, null, List.of(bind("Client", "a@x.io"))), actor()))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((ResponseStatusException) e).getReason()).contains("'Sales' is not bound");
                })
                .verify();
        verifyNoInteractions(signatureService);
    }

    @Test
    void instantiate_foreignOwner_403_notFound_404() {
        SignatureTemplate t = persisted(UUID.randomUUID());
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        StepVerifier.create(service.instantiate(t.getId(), instantiate(null, null, List.of()), SignatureService.Actor.of("s", "other@x.io")))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        UUID missing = UUID.randomUUID();
        when(repo.findById(missing)).thenReturn(Mono.empty());
        StepVerifier.create(service.instantiate(missing, instantiate(null, null, List.of()), actor()))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void toDto_roundTripsJsonColumns() {
        SignatureTemplate t = persisted(null);
        when(repo.findById(t.getId())).thenReturn(Mono.just(t));
        SignatureTemplateDTO dto = service.get(t.getId(), OWNER).block();
        assertThat(dto.roles()).containsExactly(CLIENT, SALES);
        assertThat(dto.fields()).containsExactly(CLIENT_SIG, CLIENT_DATE, SALES_SIG);
    }
}
