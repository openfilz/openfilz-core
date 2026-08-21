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
 * Registers GraalVM native image reflection hints for the official Anthropic Java SDK.
 *
 * Spring AI 2.0's {@code AnthropicChatModel} is built on {@code com.anthropic:anthropic-java-core},
 * which (unlike the Google GenAI SDK) ships no {@code META-INF/native-image} metadata and no Spring
 * AOT hints. The SDK is Kotlin + Jackson based: request/response model types are (de)serialized
 * reflectively, so without registration the first Claude call in a native image fails.
 *
 * Follows the same AOT-build-time JAR-scanning pattern as {@link PoiOoxmlRuntimeHints}: locate the
 * SDK jar via a marker class, then register every class under the packages Jackson touches.
 */
public class AnthropicSdkRuntimeHints implements RuntimeHintsRegistrar {

    /** Packages whose classes are reached reflectively (Jackson serdes + JsonValue plumbing). */
    private static final String[] SDK_PACKAGES = {
            "com/anthropic/models/",
            "com/anthropic/core/"
    };

    /** Marker class used to locate the anthropic-java-core JAR. */
    private static final String JAR_MARKER_RESOURCE = "com/anthropic/core/JsonValue.class";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        try {
            URL url = classLoader.getResource(JAR_MARKER_RESOURCE);
            if (url == null) {
                return; // SDK not on the classpath — nothing to register
            }
            if ("jar".equals(url.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                try (JarFile jarFile = connection.getJarFile()) {
                    scanJar(jarFile, hints, classLoader);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan Anthropic SDK classes for native image hints", e);
        }
    }

    private void scanJar(JarFile jarFile, RuntimeHints hints, ClassLoader classLoader) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(".class") && isSdkClass(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                registerClass(className, hints, classLoader);
            }
        }
    }

    private boolean isSdkClass(String entryName) {
        for (String prefix : SDK_PACKAGES) {
            if (entryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void registerClass(String className, RuntimeHints hints, ClassLoader classLoader) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            hints.reflection().registerType(clazz,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip classes that can't be loaded
        }
    }
}
