package org.openfilz.dms.service.ai;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image hints for {@link SignatureAiTools}: built per call (not a bean), so its
 * {@code @Tool} methods must be registered for reflection — see {@link PdfAiToolsRuntimeHints}.
 * The e-Sign request/response records it uses are already reachable through the REST controllers.
 */
public class SignatureAiToolsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(TypeReference.of(SignatureAiTools.class),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
