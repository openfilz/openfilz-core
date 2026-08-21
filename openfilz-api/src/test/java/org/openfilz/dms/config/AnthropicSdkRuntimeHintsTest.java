package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnthropicSdkRuntimeHints}. Exercises the AOT reflection-hint
 * registrar against the real anthropic-java-core jar on the test classpath.
 */
class AnthropicSdkRuntimeHintsTest {

    private final AnthropicSdkRuntimeHints registrar = new AnthropicSdkRuntimeHints();

    @Test
    void registerHints_scansSdkJar_registersModelAndCoreTypes() {
        RuntimeHints hints = new RuntimeHints();

        registrar.registerHints(hints, getClass().getClassLoader());

        long count = hints.reflection().typeHints().count();
        assertTrue(count > 0, "expected Anthropic SDK reflection hints to be registered, got " + count);
        // The marker class itself must be covered — it is part of the scanned core package.
        assertNotNull(hints.reflection().getTypeHint(TypeReference.of("com.anthropic.core.JsonValue")),
                "expected com.anthropic.core.JsonValue to be registered");
    }

    @Test
    void registerHints_whenMarkerResourceMissing_skipsGracefully() {
        ClassLoader empty = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return null;
            }

            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.emptyEnumeration();
            }
        };
        RuntimeHints hints = new RuntimeHints();

        assertDoesNotThrow(() -> registrar.registerHints(hints, empty));
        assertEquals(0, hints.reflection().typeHints().count());
    }

    @Test
    void isSdkClass_matchesSdkPackages_andRejectsOthers() {
        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(registrar, "isSdkClass",
                        "com/anthropic/models/messages/Message.class"));
        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(registrar, "isSdkClass",
                        "com/anthropic/core/JsonValue.class"));
        assertEquals(Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(registrar, "isSdkClass",
                        "com/anthropic/client/AnthropicClient.class"));
        assertEquals(Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(registrar, "isSdkClass",
                        "org/openfilz/dms/Whatever.class"));
    }

    @Test
    void registerClass_withUnknownClassName_isSwallowed() {
        RuntimeHints hints = new RuntimeHints();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                "com.does.not.Exist", hints, getClass().getClassLoader()));
        assertEquals(0, hints.reflection().typeHints().count());
    }
}
