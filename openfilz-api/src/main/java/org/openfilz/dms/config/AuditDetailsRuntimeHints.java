package org.openfilz.dms.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers GraalVM native image reflection hints for every {@link AuditLogDetails} subtype.
 * <p>
 * The audit trail stores each entry's details as JSON and reads them back through Jackson
 * ({@code JsonUtils.toAudiLogDetails}), which picks the subtype from the {@code @JsonSubTypes}
 * discriminator and builds it reflectively: no-arg constructor, then the private fields (the DTOs
 * only have Lombok getters). Nothing in the code constructs a subtype by that path, so native-image
 * cannot see that it needs those members. A subtype without hints makes {@code GET /audit/{id}} fail
 * with {@code InvalidDefinitionException: Cannot construct instance of ... WorkflowAudit} — and one such
 * entry is enough to break the whole trail of the document (seen on the demo with a workflow event).
 * <p>
 * The subtypes are read from {@code @JsonSubTypes} rather than listed here, so a new audit type is
 * covered as soon as it is declared there.
 */
public class AuditDetailsRuntimeHints implements RuntimeHintsRegistrar {

    private static final MemberCategory[] CATEGORIES = {
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_METHODS
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // The base class carries the fields shared by every entry (workflow cause, …).
        hints.reflection().registerType(AuditLogDetails.class, CATEGORIES);
        JsonSubTypes subTypes = AuditLogDetails.class.getAnnotation(JsonSubTypes.class);
        for (JsonSubTypes.Type type : subTypes.value()) {
            hints.reflection().registerType(type.value(), CATEGORIES);
        }
    }
}
