package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PoiOoxmlRuntimeHints}. Exercises the AOT reflection-hint
 * registrar against the real POI / XMLBeans jars on the test classpath.
 */
class PoiOoxmlRuntimeHintsTest {

    private final PoiOoxmlRuntimeHints registrar = new PoiOoxmlRuntimeHints();

    @Test
    void registerHints_scansJars_registersSchemaTypes() {
        RuntimeHints hints = new RuntimeHints();

        registrar.registerHints(hints, getClass().getClassLoader());

        // The poi-ooxml-lite and xmlbeans jars are on the test classpath, so the
        // jar-scanning branch must have registered a non-trivial number of types.
        long count = hints.reflection().typeHints().count();
        assertTrue(count > 0, "expected schema reflection hints to be registered, got " + count);
    }

    @Test
    void registerHints_whenMarkerResourceMissing_skipsGracefully() {
        // A classloader that resolves nothing -> every marker URL is null (continue branch).
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
    void scanClasspathPackages_registersTypesFromJars() {
        RuntimeHints hints = new RuntimeHints();

        ReflectionTestUtils.invokeMethod(registrar, "scanClasspathPackages", hints, getClass().getClassLoader());

        assertTrue(hints.reflection().typeHints().count() > 0);
    }

    @Test
    void isSchemaClass_matchesKnownPrefixes_andRejectsOthers() {
        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(registrar, "isSchemaClass",
                        "org/openxmlformats/schemas/Foo.class"));
        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(registrar, "isSchemaClass",
                        "com/microsoft/schemas/Bar.class"));
        assertEquals(Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(registrar, "isSchemaClass",
                        "org/openfilz/dms/Whatever.class"));
    }

    @Test
    void registerClass_withUnknownClassName_isSwallowed() {
        RuntimeHints hints = new RuntimeHints();

        // ClassNotFoundException path -> the class is simply skipped, no hint added, no throw.
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                "com.does.not.Exist", hints, getClass().getClassLoader()));
        assertEquals(0, hints.reflection().typeHints().count());
    }

    @Test
    void registerClass_withRealClass_registersTypeAndArrayType() {
        RuntimeHints hints = new RuntimeHints();

        ReflectionTestUtils.invokeMethod(registrar, "registerClass",
                "java.lang.String", hints, getClass().getClassLoader());

        // Both String and String[] should now be present.
        assertEquals(2, hints.reflection().typeHints().count());
    }
}
