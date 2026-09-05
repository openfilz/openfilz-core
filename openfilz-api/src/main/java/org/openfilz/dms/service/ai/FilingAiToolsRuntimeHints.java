package org.openfilz.dms.service.ai;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image hints for {@link FilingAiTools}: built per call (not a bean), so its
 * {@code @Tool} methods must be registered for reflection — see {@link PdfAiToolsRuntimeHints}
 * for the full explanation.
 * <p>
 * Without this the native image drops {@code fileDocuments} and the MCP server refuses to start:
 * {@code IllegalArgumentException: No @Tool annotated methods found in ...FilingAiTools}.
 */
public class FilingAiToolsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(TypeReference.of(FilingAiTools.class),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
