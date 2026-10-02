package org.openfilz.dms.config;

import org.openfilz.dms.service.impl.SignaturePdfServiceImpl;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.List;

/**
 * Registers GraalVM native image hints for Apache PDFBox: the encryption handlers (reflection) and
 * the TrueType fonts e-Sign stamps with (resources).
 * <p>
 * Opening an encrypted PDF — including the very common "owner password only" kind, which any
 * viewer opens without a prompt — goes through {@code SecurityHandlerFactory.newSecurityHandler},
 * which instantiates the handler registered for the document's {@code /Filter} with
 * {@code Class.getDeclaredConstructor(...).newInstance(...)}. The class comes out of a map, so
 * native-image cannot see the constructor and drops it; the load then fails with
 * {@code RuntimeException: NoSuchMethodException: StandardSecurityHandler.<init>()}.
 * <p>
 * Every PDFBox consumer shares that path — thumbnails, Tika full-text extraction (and therefore
 * smart filing and AI insights), the PDF tools and e-Sign — so a single encrypted upload lost its
 * thumbnail, its search text and its filing at once. The JVM is unaffected.
 * <p>
 * Both constructors are registered: the no-arg one used when <em>reading</em>
 * ({@code newSecurityHandlerForFilter}) and the policy one used when <em>encrypting</em>
 * ({@code newSecurityHandlerForPolicy}).
 * <p>
 * {@link SignaturePdfServiceImpl} embeds the bundled Liberation Sans TTFs into signed PDFs so
 * non-ASCII signer names are stamped as typed. Native image only ships classpath resources that
 * are registered, and the service reads them in a static initializer, so a missing pattern would
 * fail the whole e-Sign finalization.
 */
public class PdfBoxRuntimeHints implements RuntimeHintsRegistrar {

    /** Handlers {@code SecurityHandlerFactory} registers for the {@code Standard} and {@code Adobe.PubSec} filters. */
    static final List<String> SECURITY_HANDLERS = List.of(
            "org.apache.pdfbox.pdmodel.encryption.StandardSecurityHandler",
            "org.apache.pdfbox.pdmodel.encryption.PublicKeySecurityHandler");

    /** The bundled font files and their licence. */
    static final String FONT_RESOURCES = SignaturePdfServiceImpl.FONT_DIR + "*";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern(FONT_RESOURCES);
        for (String handler : SECURITY_HANDLERS) {
            hints.reflection().registerType(TypeReference.of(handler),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        }
    }
}
