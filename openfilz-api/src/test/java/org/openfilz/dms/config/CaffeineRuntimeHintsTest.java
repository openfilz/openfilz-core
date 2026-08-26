package org.openfilz.dms.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.TypeReference;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CaffeineRuntimeHints}. Exercises the AOT reflection-hint registrar
 * against the real caffeine jar on the test classpath.
 */
class CaffeineRuntimeHintsTest {

    private final CaffeineRuntimeHints registrar = new CaffeineRuntimeHints();

    /**
     * The class Caffeine resolves by name for the cache configuration used by
     * {@code UserChatClientResolver} (maximumSize + expireAfterAccess) must be registered —
     * that is the exact lookup that failed in the native image with
     * {@code ClassNotFoundException: com.github.benmanes.caffeine.cache.SSMSA}.
     */
    @Test
    void registerHints_coversTheGeneratedClassBackingOurCacheConfiguration() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();
        Object localCache = ReflectionTestUtils.getField(cache, "cache");
        assertNotNull(localCache, "unexpected Caffeine internals: no 'cache' field");
        String generatedClass = localCache.getClass().getName();
        assertEquals("com.github.benmanes.caffeine.cache.SSMSA", generatedClass,
                "cache configuration no longer maps to the class named in the native-image failure");

        RuntimeHints hints = new RuntimeHints();
        registrar.registerHints(hints, getClass().getClassLoader());

        TypeHint hint = hints.reflection().getTypeHint(TypeReference.of(generatedClass));
        assertNotNull(hint, "expected " + generatedClass + " to be registered for reflection");
        // findStaticVarHandle(clazz, "FACTORY", …) — and the constructor fallback behind it.
        assertTrue(hint.getMemberCategories().contains(MemberCategory.ACCESS_DECLARED_FIELDS));
        assertTrue(hint.getMemberCategories().contains(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
    }

    @Test
    void registerHints_registersEveryGeneratedCacheClass() {
        RuntimeHints hints = new RuntimeHints();

        registrar.registerHints(hints, getClass().getClassLoader());

        long count = hints.reflection().typeHints().count();
        assertTrue(count > 100, "expected the full generated-cache family to be registered, got " + count);
        assertTrue(hints.reflection().typeHints()
                        .allMatch(hint -> hint.getType().getName().startsWith("com.github.benmanes.caffeine.cache.")),
                "registrar must not register anything outside the Caffeine cache package");
    }

    @Test
    void isGeneratedCacheClass_matchesOptionLetterNames_andRejectsHandWrittenOnes() {
        assertEquals(Boolean.TRUE, isGenerated("com/github/benmanes/caffeine/cache/SSMSA.class"));
        assertEquals(Boolean.TRUE, isGenerated("com/github/benmanes/caffeine/cache/WI.class"));
        assertEquals(Boolean.FALSE, isGenerated("com/github/benmanes/caffeine/cache/BoundedLocalCache.class"));
        assertEquals(Boolean.FALSE, isGenerated("com/github/benmanes/caffeine/cache/Caffeine.class"));
        assertEquals(Boolean.FALSE, isGenerated("com/github/benmanes/caffeine/cache/Async$AsyncExpiry.class"));
        assertEquals(Boolean.FALSE, isGenerated("com/github/benmanes/caffeine/cache/stats/CacheStats.class"));
        assertEquals(Boolean.FALSE, isGenerated("org/openfilz/dms/Whatever.class"));
    }

    @Test
    void registerHints_whenCaffeineIsAbsent_skipsGracefully() {
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

    private Object isGenerated(String entryName) {
        return ReflectionTestUtils.invokeMethod(registrar, "isGeneratedCacheClass", entryName);
    }
}
