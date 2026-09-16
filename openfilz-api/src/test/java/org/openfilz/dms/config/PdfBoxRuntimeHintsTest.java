package org.openfilz.dms.config;

import org.apache.pdfbox.pdmodel.encryption.PublicKeyProtectionPolicy;
import org.apache.pdfbox.pdmodel.encryption.PublicKeySecurityHandler;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.encryption.StandardSecurityHandler;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the enterprise native image can exercise these hints, so a regression would otherwise only
 * show up as an encrypted upload losing its thumbnail and search text in a deployment.
 */
class PdfBoxRuntimeHintsTest {

    @Test
    void registersTheConstructorsSecurityHandlerFactoryInvokesReflectively() throws Exception {
        RuntimeHints hints = new RuntimeHints();
        new PdfBoxRuntimeHints().registerHints(hints, getClass().getClassLoader());

        // newSecurityHandlerForFilter — decrypting on load
        assertThat(RuntimeHintsPredicates.reflection()
                .onConstructorInvocation(StandardSecurityHandler.class.getDeclaredConstructor())).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onConstructorInvocation(PublicKeySecurityHandler.class.getDeclaredConstructor())).accepts(hints);
        // newSecurityHandlerForPolicy — encrypting on save
        assertThat(RuntimeHintsPredicates.reflection()
                .onConstructorInvocation(StandardSecurityHandler.class
                        .getDeclaredConstructor(StandardProtectionPolicy.class))).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onConstructorInvocation(PublicKeySecurityHandler.class
                        .getDeclaredConstructor(PublicKeyProtectionPolicy.class))).accepts(hints);
    }
}
