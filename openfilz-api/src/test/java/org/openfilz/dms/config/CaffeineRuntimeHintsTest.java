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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CaffeineRuntimeHints}. Exercises the AOT reflection-hint registrar
 * against the real caffeine jar on the test classpath.
 */
class CaffeineRuntimeHintsTest {

    private final CaffeineRuntimeHints registrar = new CaffeineRuntimeHints();

    /**
     * Both classes Caffeine resolves by name for the cache configuration used by
     * {@code UserChatClientResolver} (maximumSize + expireAfterAccess) must be registered: the
     * BoundedLocalCache subclass (SSMSA) resolved by LocalCacheFactory, and the entry class
     * (PSAMS) resolved by NodeFactory. Registering only the first is what made v1.8.7 still fail
     * in the native image, one frame further on.
     */
    @Test
    void registerHints_coversEveryClassOurCacheConfigurationResolvesByName() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(Duration.ofMinutes(30))
                .build();
        Object localCache = ReflectionTestUtils.getField(cache, "cache");
        assertNotNull(localCache, "unexpected Caffeine internals: no 'cache' field");
        Object nodeFactory = ReflectionTestUtils.getField(localCache, "nodeFactory");
        assertNotNull(nodeFactory, "unexpected Caffeine internals: no 'nodeFactory' field");

        List<String> reflectivelyResolved =
                List.of(localCache.getClass().getName(), nodeFactory.getClass().getName());
        assertEquals(List.of("com.github.benmanes.caffeine.cache.SSMSA",
                        "com.github.benmanes.caffeine.cache.PSAMS"),
                reflectivelyResolved,
                "cache configuration no longer maps to the classes named in the native-image failures");

        RuntimeHints hints = new RuntimeHints();
        registrar.registerHints(hints, getClass().getClassLoader());

        for (String className : reflectivelyResolved) {
            TypeHint hint = hints.reflection().getTypeHint(TypeReference.of(className));
            assertNotNull(hint, "expected " + className + " to be registered for reflection");
            // findStaticVarHandle(clazz, "FACTORY", …), and the constructor lookups behind it.
            assertTrue(hint.getMemberCategories().contains(MemberCategory.ACCESS_DECLARED_FIELDS));
            assertTrue(hint.getMemberCategories().contains(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
        }
    }

    @Test
    void registerHints_registersTheWholeCachePackage() {
        RuntimeHints hints = new RuntimeHints();

        registrar.registerHints(hints, getClass().getClassLoader());

        long count = hints.reflection().typeHints().count();
        assertTrue(count > 500, "expected both generated families to be registered, got " + count);
        assertTrue(hints.reflection().typeHints()
                        .allMatch(hint -> hint.getType().getName().startsWith("com.github.benmanes.caffeine.cache.")),
                "registrar must not register anything outside the Caffeine cache package");
    }

    @Test
    void isCacheClass_matchesThePackageItself_andRejectsSubpackagesAndOtherLibraries() {
        assertEquals(Boolean.TRUE, isCacheClass("com/github/benmanes/caffeine/cache/SSMSA.class"));
        assertEquals(Boolean.TRUE, isCacheClass("com/github/benmanes/caffeine/cache/PSAMS.class"));
        assertEquals(Boolean.TRUE, isCacheClass("com/github/benmanes/caffeine/cache/BoundedLocalCache.class"));
        assertEquals(Boolean.FALSE, isCacheClass("com/github/benmanes/caffeine/cache/stats/CacheStats.class"));
        assertEquals(Boolean.FALSE, isCacheClass("com/github/benmanes/caffeine/cache/Caffeine.properties"));
        assertEquals(Boolean.FALSE, isCacheClass("org/openfilz/dms/Whatever.class"));
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

    private Object isCacheClass(String entryName) {
        return ReflectionTestUtils.invokeMethod(registrar, "isCacheClass", entryName);
    }
}
