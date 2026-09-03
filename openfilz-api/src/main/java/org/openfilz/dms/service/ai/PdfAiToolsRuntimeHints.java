package org.openfilz.dms.service.ai;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image hints for {@link PdfAiTools}.
 * <p>
 * Spring AI discovers a tool object's {@code @Tool} methods reflectively
 * ({@code MethodToolCallbackProvider} scans {@code Class.getDeclaredMethods()}). {@code PdfAiTools}
 * is not a bean — it is built per call by {@code PdfAiToolsContributor} and handed back as
 * {@code Object} — so AOT never sees the concrete type and native-image would drop its methods.
 * Registering the type for declared-method and declared-constructor reflection restores the scan.
 * Core is JVM-only; this is compiled into the enterprise native image.
 */
public class PdfAiToolsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(TypeReference.of(PdfAiTools.class),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
