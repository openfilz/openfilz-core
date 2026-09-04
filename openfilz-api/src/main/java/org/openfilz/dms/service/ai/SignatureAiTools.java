package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.dto.response.pdf.PdfInfo;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.InstantiateTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureFieldInput;
import org.openfilz.dms.dto.signature.SignatureRecipientDTO;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.exception.AbstractOpenFilzException;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.PdfToolsService;
import org.openfilz.dms.service.SignatureService;
import org.openfilz.dms.service.SignatureTemplateService;
import org.openfilz.dms.service.ai.SignatureRecipientParser.Recipient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * e-Sign tools, shared by the in-app assistant and the MCP server: send a PDF for signature and
 * follow the envelopes. A thin translation layer over {@link SignatureService} and
 * {@link SignatureTemplateService}, with the same role gate as the REST endpoints
 * ({@link AiToolRolePolicy}: {@code SIGNATURE_WRITE} = CONTRIBUTOR, plus SIGN_REQUESTER when the
 * deployment requires it) and the caller's document scope ({@link AiAccessPolicy}).
 * <p>
 * Field placement is the part a model cannot do — an envelope needs normalised page coordinates
 * for every field — so {@link #sendForSignature} takes one of two routes: a <b>template</b> the
 * user prepared in the app (its fields are already placed; the model only binds roles to people),
 * or a <b>default placement</b> with one signature field per signer, stacked at the bottom of the
 * last page.
 * <p>
 * Built per request by {@code SignatureAiToolsContributor}, bound with {@link #forUser}.
 */
@Slf4j
public class SignatureAiTools {

    private static final int MAX_LISTED = 30;

    private final SignatureService signatureService;
    private final SignatureTemplateService templateService;
    private final DocumentRepository documentRepository;
    private final PdfToolsService pdfToolsService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final SignatureProperties props;

    private String userEmail;
    private Authentication authentication;

    public SignatureAiTools(SignatureService signatureService, SignatureTemplateService templateService,
                            DocumentRepository documentRepository, PdfToolsService pdfToolsService,
                            AiAccessPolicy accessPolicy, AiToolRolePolicy rolePolicy, SignatureProperties props) {
        this.signatureService = signatureService;
        this.templateService = templateService;
        this.documentRepository = documentRepository;
        this.pdfToolsService = pdfToolsService;
        this.accessPolicy = accessPolicy;
        this.rolePolicy = rolePolicy;
        this.props = props;
    }

    public SignatureAiTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    // ── tools ───────────────────────────────────────────────────────────────

    @Tool(description = "Send a PDF document for electronic signature (e-Sign). Recipients: 'Alice Smith <alice@x.com>, "
            + "bob@y.com' (prefix 'cc:' for a copy recipient who does not sign). Without a template, one signature "
            + "field per signer is placed at the bottom of the last page. With a template (see listSignatureTemplates), "
            + "bind its roles: 'Tenant: alice@x.com; Landlord: bob@y.com' (or one recipient per role, in order). "
            + "Recipients receive the signing link by email unless sendNow is false (kept as a draft).")
    public String sendForSignature(
            @ToolParam(required = false, description = "Name (or id) of the PDF to sign; may be omitted when the template has a default document") String document,
            @ToolParam(description = "The recipients, separated by ',' or ';' — name <email>, optionally prefixed by 'role:'") String recipients,
            @ToolParam(required = false, description = "Name (or id) of an e-Sign template whose fields are already placed") String template,
            @ToolParam(required = false, description = "Envelope title shown to recipients (default: the document name)") String title,
            @ToolParam(required = false, description = "Personal message included in the invitation email") String message,
            @ToolParam(required = false, description = "Days before the request expires (1-365; deployment default when omitted)") Integer expiresInDays,
            @ToolParam(required = false, description = "true = recipients sign one after the other, in the given order (default false)") Boolean sequential,
            @ToolParam(required = false, description = "false = keep as a DRAFT without emailing anyone (default true)") Boolean sendNow) {
        String denial = deny("sendForSignature", ToolCapability.SIGNATURE_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            List<Recipient> parsed = SignatureRecipientParser.parse(recipients);
            if (parsed.isEmpty()) return "Give at least one recipient, e.g. 'Alice Smith <alice@example.com>'.";
            if (parsed.stream().noneMatch(r -> !r.cc())) return "At least one recipient must be a signer (not 'cc:').";

            SignatureTemplateDTO tpl = null;
            if (template != null && !template.isBlank()) {
                TemplateLookup lookup = resolveTemplate(template);
                if (lookup.error() != null) return lookup.error();
                tpl = lookup.template();
            }
            Document doc = null;
            if (document != null && !document.isBlank()) {
                Lookup lookup = resolvePdf(document);
                if (lookup.error() != null) return lookup.error();
                doc = lookup.document();
            } else if (tpl == null || tpl.sourceDocId() == null) {
                return "Say which PDF document to send for signature (name or id).";
            }
            String envelopeTitle = title != null && !title.isBlank() ? title.trim()
                    : doc != null ? stripExtension(doc.getName()) : tpl.name();
            Boolean send = sendNow == null ? null : sendNow;
            SignatureService.Actor actor = actor();

            SignatureEnvelopeDTO envelope;
            if (tpl != null) {
                List<InstantiateTemplateRequest.RoleBinding> bindings = bindRoles(tpl, parsed);
                envelope = blockWithAuth(templateService.instantiate(tpl.id(), new InstantiateTemplateRequest(
                        doc != null ? doc.getId() : null, envelopeTitle, message, bindings, expiresInDays, null, null, send), actor));
            } else {
                int pages = pageCount(doc);
                List<SignatureRecipientInput> inputs = defaultPlacement(parsed, pages, Boolean.TRUE.equals(sequential));
                envelope = blockWithAuth(signatureService.create(new CreateSignatureEnvelopeRequest(
                        doc.getId(), envelopeTitle, message, inputs, expiresInDays, sequential, null, null, send, null), actor));
            }
            return describe(envelope, true);
        });
    }

    @Tool(description = "List the e-Sign templates of the current user (reusable envelopes with the signature fields "
            + "already placed): name, id, the roles to bind to recipients, and whether a default document is attached.")
    public String listSignatureTemplates() {
        String denial = deny("listSignatureTemplates", ToolCapability.SIGNATURE_READ);
        if (denial != null) return denial;
        return run(() -> {
            List<SignatureTemplateDTO> templates = blockWithAuth(templateService.list(userEmail).collectList());
            if (templates == null || templates.isEmpty()) {
                return "You have no e-Sign templates. sendForSignature works without one: a signature field per "
                        + "signer is placed at the bottom of the last page.";
            }
            return templates.stream().limit(MAX_LISTED).map(t -> "- '" + t.name() + "' (id " + t.id() + "): roles "
                    + roleNames(t) + (t.sourceDocId() != null ? "; default document attached" : "; no default document")
                    + (t.sequential() ? "; sequential signing" : "")
                    + (t.description() != null && !t.description().isBlank() ? " — " + t.description() : ""))
                    .collect(Collectors.joining("\n"));
        });
    }

    @Tool(description = "List e-Sign envelopes: those the current user sent (optionally filtered by status: DRAFT, SENT, "
            + "COMPLETED, DECLINED, CANCELLED, EXPIRED) or, with 'to-sign', those waiting for the user's own signature.")
    public String listSignatureEnvelopes(
            @ToolParam(required = false, description = "A status (DRAFT, SENT, COMPLETED, DECLINED, CANCELLED, EXPIRED), 'to-sign', or omit for all sent envelopes") String status) {
        String denial = deny("listSignatureEnvelopes", ToolCapability.SIGNATURE_READ);
        if (denial != null) return denial;
        return run(() -> {
            List<SignatureEnvelopeDTO> envelopes;
            String label;
            String filter = status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (filter.equals("to-sign") || filter.equals("tosign") || filter.equals("waiting") || filter.equals("mine")) {
                envelopes = blockWithAuth(signatureService.listToSign(userEmail).collectList());
                label = "waiting for your signature";
            } else if (filter.isEmpty() || filter.equals("all") || filter.equals("sent-by-me")) {
                envelopes = blockWithAuth(signatureService.listSent(userEmail, null).collectList());
                label = "you sent";
            } else {
                SignatureEnvelopeStatus parsed;
                try {
                    parsed = SignatureEnvelopeStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "Unknown status '" + status + "'. Use DRAFT, SENT, COMPLETED, DECLINED, CANCELLED, EXPIRED or 'to-sign'.";
                }
                envelopes = blockWithAuth(signatureService.listSent(userEmail, parsed).collectList());
                label = "you sent with status " + parsed;
            }
            if (envelopes == null || envelopes.isEmpty()) {
                return "No envelopes " + label + ".";
            }
            List<SignatureEnvelopeDTO> sorted = envelopes.stream()
                    .sorted(Comparator.comparing(SignatureEnvelopeDTO::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            String lines = sorted.stream().limit(MAX_LISTED).map(SignatureAiTools::summary).collect(Collectors.joining("\n"));
            return envelopes.size() + " envelope(s) " + label + (envelopes.size() > MAX_LISTED ? " (showing " + MAX_LISTED + ")" : "")
                    + ":\n" + lines;
        });
    }

    @Tool(description = "Get the status of an e-Sign envelope: who has viewed, signed or declined, when it expires, and "
            + "where the signed PDF is once completed.")
    public String getSignatureStatus(
            @ToolParam(description = "Envelope id or title") String envelope) {
        String denial = deny("getSignatureStatus", ToolCapability.SIGNATURE_READ);
        if (denial != null) return denial;
        return run(() -> {
            if (envelope == null || envelope.isBlank()) return "Give the envelope id or title.";
            UUID id = parseUuid(envelope.trim());
            SignatureEnvelopeDTO dto;
            if (id != null) {
                dto = blockWithAuth(signatureService.get(id, userEmail));
            } else {
                List<SignatureEnvelopeDTO> sent = blockWithAuth(signatureService.listSent(userEmail, null).collectList());
                List<SignatureEnvelopeDTO> candidates = sent == null ? List.of() : sent;
                List<SignatureEnvelopeDTO> exact = candidates.stream()
                        .filter(e -> e.title() != null && e.title().equalsIgnoreCase(envelope.trim())).toList();
                if (exact.isEmpty()) {
                    exact = candidates.stream()
                            .filter(e -> e.title() != null && e.title().toLowerCase(Locale.ROOT).contains(envelope.trim().toLowerCase(Locale.ROOT)))
                            .toList();
                }
                if (exact.isEmpty()) return "No envelope titled '" + envelope + "' among those you sent. Use listSignatureEnvelopes.";
                if (exact.size() > 1) {
                    return "Several envelopes match '" + envelope + "': " + exact.stream().limit(8)
                            .map(e -> "'" + e.title() + "' (id " + e.id() + ", " + e.status() + ")").collect(Collectors.joining(", "))
                            + ". Use the id.";
                }
                dto = blockWithAuth(signatureService.get(exact.getFirst().id(), userEmail));
            }
            if (dto == null) return "Envelope not found.";
            return describe(dto, false);
        });
    }

    // ── envelope building ───────────────────────────────────────────────────

    /**
     * One SIGNATURE field per signer at the bottom of the last page, stacked upwards (PDF
     * coordinates are normalised, origin bottom-left); a second column once the first is full.
     */
    static List<SignatureRecipientInput> defaultPlacement(List<Recipient> recipients, int pages, boolean sequential) {
        List<SignatureRecipientInput> inputs = new ArrayList<>();
        int signerIndex = 0;
        int lastPage = Math.max(0, pages - 1);
        for (Recipient recipient : recipients) {
            if (recipient.cc()) {
                inputs.add(new SignatureRecipientInput(null, recipient.name(), recipient.email(), 0,
                        SignatureRecipientRole.CC, null, null, null, List.of(), null));
                continue;
            }
            int column = signerIndex / 8;
            int row = signerIndex % 8;
            double x = column == 0 ? 0.08 : 0.56;
            double y = 0.05 + row * 0.11;
            SignatureFieldInput field = new SignatureFieldInput(SignatureFieldType.SIGNATURE, lastPage, x, y, 0.36, 0.08,
                    true, "Signature", null);
            inputs.add(new SignatureRecipientInput(null, recipient.name(), recipient.email(),
                    sequential ? signerIndex : 0, SignatureRecipientRole.SIGNER, null, null, null, List.of(field), null));
            signerIndex++;
        }
        return inputs;
    }

    /** Bind the template's roles to people: by role name when given, else one recipient per role in order. */
    static List<InstantiateTemplateRequest.RoleBinding> bindRoles(SignatureTemplateDTO template, List<Recipient> recipients) {
        List<SignatureTemplateRole> roles = template.roles() == null ? List.of() : template.roles().stream()
                .sorted(Comparator.comparingInt(r -> r.orderIndex() == null ? 0 : r.orderIndex()))
                .toList();
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("Template '" + template.name() + "' defines no roles.");
        }
        List<Recipient> signers = recipients.stream().filter(r -> !r.cc()).toList();
        boolean allNamed = signers.stream().allMatch(r -> r.role() != null);
        boolean noneNamed = signers.stream().noneMatch(r -> r.role() != null);
        Map<String, SignatureTemplateRole> byName = new HashMap<>();
        roles.forEach(r -> byName.put(r.name().toLowerCase(Locale.ROOT), r));
        List<InstantiateTemplateRequest.RoleBinding> bindings = new ArrayList<>();
        if (allNamed) {
            for (Recipient r : signers) {
                SignatureTemplateRole role = byName.get(r.role().toLowerCase(Locale.ROOT));
                if (role == null) {
                    throw new IllegalArgumentException("Template '" + template.name() + "' has no role '" + r.role()
                            + "'. Its roles are: " + roleNames(template) + ".");
                }
                bindings.add(new InstantiateTemplateRequest.RoleBinding(role.name(), null, r.name(), r.email(), null, null));
            }
        } else if (noneNamed && signers.size() == roles.size()) {
            for (int i = 0; i < roles.size(); i++) {
                Recipient r = signers.get(i);
                bindings.add(new InstantiateTemplateRequest.RoleBinding(roles.get(i).name(), null, r.name(), r.email(), null, null));
            }
        } else {
            throw new IllegalArgumentException("Template '" + template.name() + "' expects one recipient per role ("
                    + roleNames(template) + "). Give them as 'Role: name <email>' for each role.");
        }
        List<String> missing = roles.stream().map(SignatureTemplateRole::name)
                .filter(name -> bindings.stream().noneMatch(b -> b.role().equalsIgnoreCase(name))).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No recipient given for role(s) " + String.join(", ", missing)
                    + " of template '" + template.name() + "'.");
        }
        if (recipients.stream().anyMatch(Recipient::cc)) {
            log.debug("[AI-TOOL] sendForSignature: 'cc' recipients are ignored when a template is used");
        }
        return bindings;
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private static String describe(SignatureEnvelopeDTO env, boolean justCreated) {
        StringBuilder sb = new StringBuilder();
        sb.append("Envelope '").append(env.title()).append("' (id ").append(env.id()).append(") — ").append(env.status());
        if (justCreated) {
            sb.append(env.status() == SignatureEnvelopeStatus.DRAFT
                    ? ". Kept as a draft: nobody has been emailed; the user can send it from the e-Sign page."
                    : ". Signing invitations have been emailed to the recipients.");
        } else {
            sb.append('.');
        }
        if (env.sourceDocId() != null) sb.append(" Source document id ").append(env.sourceDocId()).append('.');
        if (env.recipients() != null && !env.recipients().isEmpty()) {
            sb.append("\nRecipients").append(env.sequential() ? " (sign in order)" : "").append(":");
            for (SignatureRecipientDTO r : env.recipients()) {
                sb.append("\n  - ").append(r.name() != null ? r.name() + " <" + r.email() + ">" : r.email());
                sb.append(r.role() == SignatureRecipientRole.CC ? " (copy)" : " (signer)");
                sb.append(": ").append(r.status());
                if (r.signedAt() != null) sb.append(" on ").append(r.signedAt().toLocalDate());
                else if (r.viewedAt() != null) sb.append(", viewed ").append(r.viewedAt().toLocalDate());
                if (r.declineReason() != null) sb.append(" — reason: ").append(r.declineReason());
            }
        }
        sb.append('\n');
        if (env.sentAt() != null) sb.append("Sent ").append(env.sentAt().toLocalDate()).append(". ");
        if (env.expiresAt() != null && env.status() == SignatureEnvelopeStatus.SENT) sb.append("Expires ").append(env.expiresAt().toLocalDate()).append(". ");
        if (env.completedAt() != null) sb.append("Completed ").append(env.completedAt().toLocalDate()).append(". ");
        if (env.signedDocId() != null) sb.append("Signed PDF stored as document id ").append(env.signedDocId()).append(". ");
        return sb.toString().trim();
    }

    private static String summary(SignatureEnvelopeDTO env) {
        long signers = env.recipients() == null ? 0 : env.recipients().stream().filter(r -> r.role() != SignatureRecipientRole.CC).count();
        long signed = env.recipients() == null ? 0 : env.recipients().stream()
                .filter(r -> r.role() != SignatureRecipientRole.CC && r.signedAt() != null).count();
        StringBuilder sb = new StringBuilder("- '").append(env.title()).append("' (id ").append(env.id()).append("): ").append(env.status());
        if (signers > 0) sb.append(", ").append(signed).append('/').append(signers).append(" signed");
        OffsetDateTime when = env.sentAt() != null ? env.sentAt() : env.createdAt();
        if (when != null) sb.append(", ").append(env.sentAt() != null ? "sent " : "created ").append(when.toLocalDate());
        if (env.expiresAt() != null && env.status() == SignatureEnvelopeStatus.SENT) sb.append(", expires ").append(env.expiresAt().toLocalDate());
        return sb.toString();
    }

    private static String roleNames(SignatureTemplateDTO template) {
        return template.roles() == null ? "(none)" : template.roles().stream().map(SignatureTemplateRole::name)
                .collect(Collectors.joining(", "));
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    record Lookup(Document document, String error) {
    }

    record TemplateLookup(SignatureTemplateDTO template, String error) {
    }

    private interface ToolBody {
        String call() throws Exception;
    }

    private String run(ToolBody body) {
        if (!props.isActive()) {
            return "e-Sign is disabled on this OpenFilz deployment (openfilz.signature.active=false).";
        }
        if (userEmail == null || userEmail.isBlank()) {
            return "Not permitted: e-Sign needs an authenticated user with an email address.";
        }
        try {
            return body.call();
        } catch (ResponseStatusException e) {
            log.debug("[AI-TOOL] e-Sign tool refused: {}", e.getReason());
            return "Could not perform the operation: " + (e.getReason() != null ? e.getReason() : e.getMessage());
        } catch (AbstractOpenFilzException | IllegalArgumentException | IllegalStateException e) {
            log.debug("[AI-TOOL] e-Sign tool refused: {}", e.getMessage());
            return "Could not perform the operation: " + e.getMessage();
        } catch (Exception e) {
            log.error("[AI-TOOL] e-Sign tool failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private String deny(String toolName, ToolCapability capability) {
        if (rolePolicy == null || rolePolicy.isAllowed(authentication, capability)) {
            return null;
        }
        log.warn("[AI-TOOL] {} refused: caller lacks the role for {}", toolName, capability);
        return "Not permitted: your OpenFilz role does not allow this operation (" + capability
                + "). Ask an administrator for the required role.";
    }

    private SignatureService.Actor actor() {
        String subject = null;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            subject = jwt.getSubject();
        }
        return SignatureService.Actor.of(subject, userEmail);
    }

    private <T> T blockWithAuth(Mono<T> mono) {
        return (authentication != null
                ? mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                : mono).block();
    }

    private boolean canRead(UUID documentId) {
        return Boolean.TRUE.equals(accessPolicy.canRead(documentId, userEmail).block());
    }

    private int pageCount(Document document) {
        PdfInfo info = blockWithAuth(pdfToolsService.info(document.getId()));
        if (info == null) return 1;
        if (info.encrypted()) {
            throw new IllegalArgumentException("'" + document.getName() + "' is password-protected and cannot be sent for signature");
        }
        return Math.max(1, info.pageCount());
    }

    private Lookup resolvePdf(String nameOrId) {
        UUID id = parseUuid(nameOrId.trim());
        List<Document> candidates;
        if (id != null) {
            Document byId = blockWithAuth(documentRepository.findByIdAndActive(id, true));
            candidates = byId != null ? List.of(byId) : List.of();
        } else {
            List<Document> found = blockWithAuth(documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(nameOrId.trim()).collectList());
            candidates = found != null ? found : List.of();
        }
        List<Document> pdfs = candidates.stream()
                .filter(d -> d.getType() == DocumentType.FILE && isPdf(d) && canRead(d.getId()))
                .toList();
        if (pdfs.isEmpty()) {
            return new Lookup(null, "No PDF document matching '" + nameOrId + "' was found (or you cannot access it). "
                    + "Only PDFs can be sent for signature; use queryDocuments to find the exact name.");
        }
        if (pdfs.size() == 1) {
            return new Lookup(pdfs.getFirst(), null);
        }
        List<Document> exact = pdfs.stream().filter(d -> d.getName().equalsIgnoreCase(nameOrId.trim())).toList();
        if (exact.size() == 1) {
            return new Lookup(exact.getFirst(), null);
        }
        return new Lookup(null, "Several PDFs match '" + nameOrId + "': "
                + pdfs.stream().limit(8).map(d -> "'" + d.getName() + "' (id " + d.getId() + ")").collect(Collectors.joining(", "))
                + ". Use the id.");
    }

    private TemplateLookup resolveTemplate(String nameOrId) {
        List<SignatureTemplateDTO> templates = blockWithAuth(templateService.list(userEmail).collectList());
        if (templates == null || templates.isEmpty()) {
            return new TemplateLookup(null, "You have no e-Sign templates. Omit the template to use the default placement.");
        }
        UUID id = parseUuid(nameOrId.trim());
        if (id != null) {
            return templates.stream().filter(t -> id.equals(t.id())).findFirst()
                    .map(t -> new TemplateLookup(t, null))
                    .orElse(new TemplateLookup(null, "No template with id " + id + " belongs to you."));
        }
        List<SignatureTemplateDTO> exact = templates.stream().filter(t -> t.name().equalsIgnoreCase(nameOrId.trim())).toList();
        if (exact.size() == 1) return new TemplateLookup(exact.getFirst(), null);
        List<SignatureTemplateDTO> partial = templates.stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(nameOrId.trim().toLowerCase(Locale.ROOT))).toList();
        if (partial.size() == 1) return new TemplateLookup(partial.getFirst(), null);
        if (partial.isEmpty()) {
            return new TemplateLookup(null, "No template named '" + nameOrId + "'. Your templates: "
                    + templates.stream().map(t -> "'" + t.name() + "'").collect(Collectors.joining(", ")) + ".");
        }
        return new TemplateLookup(null, "Several templates match '" + nameOrId + "': "
                + partial.stream().map(t -> "'" + t.name() + "' (id " + t.id() + ")").collect(Collectors.joining(", ")) + ". Use the id.");
    }

    private static boolean isPdf(Document d) {
        return "application/pdf".equalsIgnoreCase(d.getContentType())
                || (d.getName() != null && d.getName().toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private static String stripExtension(String name) {
        if (name == null) return "Signature request";
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
