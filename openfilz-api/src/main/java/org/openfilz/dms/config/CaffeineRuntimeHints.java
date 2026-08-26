package org.openfilz.dms.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Registers GraalVM native image reflection hints for Caffeine's generated cache classes.
 * <p>
 * Caffeine builds a cache out of two families of generated classes, and resolves <em>both</em> by
 * name at runtime from the builder options in effect:
 * <ul>
 *   <li>{@code LocalCacheFactory} → the {@code BoundedLocalCache} subclass ({@code SSMSA} for
 *       {@code maximumSize + expireAfterAccess}), via {@code findClass} then
 *       {@code findStaticVarHandle(clazz, "FACTORY")} with {@code findConstructor} as fallback;</li>
 *   <li>{@code NodeFactory} → the entry class ({@code PSAMS} for the same options), via
 *       {@code findClass} then {@code findConstructor}.</li>
 * </ul>
 * Every one of those lookups takes a non-constant class, so native-image cannot intrinsify them and
 * drops the generated classes as unreachable; the first {@code Caffeine.build()} then fails at
 * runtime with {@code IllegalStateException: <name>} caused by {@code ClassNotFoundException}. The
 * JVM is unaffected, so this only ever shows up in a deployed native image.
 * <p>
 * The whole {@code com.github.benmanes.caffeine.cache} package is registered rather than the class
 * names we can predict: the two families use different naming alphabets (the cache one is built
 * from {@code S W I L M A R}, the node one from {@code P F D A M S W R …}), a v1.8.7 fix that
 * modelled only the first shipped a native image that still died on the second. Registering the
 * package needs no model of either scheme and survives Caffeine changing them.
 */
public class CaffeineRuntimeHints implements RuntimeHintsRegistrar {

    /** Package holding both the hand-written and the two families of generated classes. */
    private static final String CACHE_PACKAGE = "com/github/benmanes/caffeine/cache/";

    /** Marker class used to locate the caffeine JAR. */
    private static final String JAR_MARKER_RESOURCE = CACHE_PACKAGE + "Caffeine.class";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        try {
            URL url = classLoader.getResource(JAR_MARKER_RESOURCE);
            if (url == null || !"jar".equals(url.getProtocol())) {
                return; // Caffeine absent, or exploded on disk (dev run) — nothing to register
            }
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            try (JarFile jarFile = connection.getJarFile()) {
                scanJar(jarFile, hints, classLoader);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan Caffeine classes for native image hints", e);
        }
    }

    private void scanJar(JarFile jarFile, RuntimeHints hints, ClassLoader classLoader) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (isCacheClass(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                registerClass(className, hints, classLoader);
            }
        }
    }

    /** Classes directly in the cache package — where both generated families live. */
    private boolean isCacheClass(String entryName) {
        return entryName.startsWith(CACHE_PACKAGE)
                && entryName.endsWith(".class")
                && entryName.indexOf('/', CACHE_PACKAGE.length()) < 0;
    }

    private void registerClass(String className, RuntimeHints hints, ClassLoader classLoader) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            // Fields: the static FACTORY VarHandle lookup (and the per-class field VarHandles).
            // Constructors: NodeFactory's no-arg lookup, and LocalCacheFactory's fallback.
            hints.reflection().registerType(clazz,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip classes that can't be loaded
        }
    }
}
