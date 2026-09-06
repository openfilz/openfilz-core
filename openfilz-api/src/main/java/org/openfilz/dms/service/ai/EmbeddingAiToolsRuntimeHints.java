package org.openfilz.dms.service.ai;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image hints for {@link EmbeddingAiTools}: built per call (not a bean), so its
 * {@code @Tool} methods must be registered for reflection — see {@link PdfAiToolsRuntimeHints}
 * for the full explanation. Without this the native image drops {@code backfillEmbeddings} and
 * the MCP server refuses to start.
 */
public class EmbeddingAiToolsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(TypeReference.of(EmbeddingAiTools.class),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
