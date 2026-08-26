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
import java.util.regex.Pattern;

/**
 * Registers GraalVM native image reflection hints for Caffeine's generated cache classes.
 * <p>
 * Caffeine does not instantiate {@code BoundedLocalCache} directly: {@code LocalCacheFactory}
 * derives a class <em>name</em> from the builder options chosen at runtime (keys/values strength,
 * removal listener, stats, eviction, expiry, refresh — e.g. {@code SSMSA} for
 * {@code maximumSize + expireAfterAccess}) and then resolves it with
 * {@code MethodHandles.Lookup.findClass}, followed by {@code findStaticVarHandle(clazz, "FACTORY")}
 * and, as a fallback, {@code findConstructor}. All three lookups take a non-constant class, so
 * native-image cannot intrinsify them and the classes are dropped as unreachable — the first
 * {@code Caffeine.build()} then dies with
 * {@code IllegalStateException: SSMSA / ClassNotFoundException: …cache.SSMSA}.
 * <p>
 * Since the exact combination depends on runtime configuration (and on whatever Caffeine caches
 * our dependencies build), every generated class is registered rather than the few we happen to
 * use today. Their names are made up solely of the option letters {@code S W I L M A R}, which
 * distinguishes them from Caffeine's hand-written classes.
 *
 * @see <a href="https://github.com/ben-manes/caffeine/blob/master/caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalCacheFactory.java">LocalCacheFactory</a>
 */
public class CaffeineRuntimeHints implements RuntimeHintsRegistrar {

    /** Package holding both the hand-written and the generated cache classes. */
    private static final String CACHE_PACKAGE = "com/github/benmanes/caffeine/cache/";

    /** Marker class used to locate the caffeine JAR. */
    private static final String JAR_MARKER_RESOURCE = CACHE_PACKAGE + "Caffeine.class";

    /**
     * Simple names of the generated cache classes: one letter per builder option
     * ({@code S}trong/{@code W}eak keys, {@code S}trong/{@code I}nterned values, removal
     * {@code L}istener, {@code S}tats, {@code M}aximum + {@code S}ize/{@code W}eight,
     * expires-after-{@code A}ccess, expires-after-{@code W}rite, {@code R}efresh).
     */
    private static final Pattern GENERATED_CACHE_NAME = Pattern.compile("[SWILMAR]{2,8}");

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
            if (isGeneratedCacheClass(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                registerClass(className, hints, classLoader);
            }
        }
    }

    private boolean isGeneratedCacheClass(String entryName) {
        if (!entryName.startsWith(CACHE_PACKAGE) || !entryName.endsWith(".class")) {
            return false;
        }
        String simpleName = entryName.substring(CACHE_PACKAGE.length(), entryName.length() - ".class".length());
        return GENERATED_CACHE_NAME.matcher(simpleName).matches();
    }

    private void registerClass(String className, RuntimeHints hints, ClassLoader classLoader) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            // Fields: the static FACTORY VarHandle lookup (and the per-class field VarHandles).
            // Constructors: the MethodHandleBasedFactory fallback taken when FACTORY is not found.
            hints.reflection().registerType(clazz,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip classes that can't be loaded
        }
    }
}
