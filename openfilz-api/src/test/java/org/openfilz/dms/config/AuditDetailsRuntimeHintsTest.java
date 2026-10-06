package org.openfilz.dms.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.openfilz.dms.dto.audit.WorkflowAudit;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the enterprise native image can exercise these hints: without them the audit trail of a
 * document fails with {@code InvalidDefinitionException} as soon as one entry has details of an
 * unregistered type (a workflow event on the demo).
 */
class AuditDetailsRuntimeHintsTest {

    @Test
    void registersEveryDeclaredSubtypeForJacksonDeserialization() throws Exception {
        RuntimeHints hints = new RuntimeHints();
        new AuditDetailsRuntimeHints().registerHints(hints, getClass().getClassLoader());

        JsonSubTypes subTypes = AuditLogDetails.class.getAnnotation(JsonSubTypes.class);
        assertThat(subTypes.value()).isNotEmpty();
        for (JsonSubTypes.Type type : subTypes.value()) {
            Class<?> subtype = type.value();
            assertThat(RuntimeHintsPredicates.reflection().onType(subtype)
                    .withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.ACCESS_DECLARED_FIELDS))
                    .as(subtype.getSimpleName()).accepts(hints);
            assertThat(RuntimeHintsPredicates.reflection()
                    .onConstructorInvocation(subtype.getDeclaredConstructor()))
                    .as(subtype.getSimpleName() + " no-arg constructor").accepts(hints);
        }
        // The base class holds the fields every entry shares (the workflow cause).
        assertThat(RuntimeHintsPredicates.reflection().onType(AuditLogDetails.class)
                .withMemberCategories(MemberCategory.ACCESS_DECLARED_FIELDS)).accepts(hints);
    }

    @Test
    void coversTheWorkflowAuditThatBrokeTheDemo() {
        RuntimeHints hints = new RuntimeHints();
        new AuditDetailsRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(WorkflowAudit.class)
                .withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.ACCESS_DECLARED_FIELDS)).accepts(hints);
    }
}
