package org.openfilz.dms.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * What the in-process embedding provider needs in a GraalVM native image: ONNX Runtime and the
 * Hugging Face tokenizers are JNI libraries that call back into their Java classes, and both
 * ship their {@code .so} inside the jar and extract it at first use — so every class of
 * {@code ai.onnxruntime} and of the tokenizer bindings is registered for reflection <b>and</b>
 * JNI, and the native libraries (Linux, the image's platform) are kept as resources. The
 * enterprise image also initialises {@code ai.onnxruntime} and {@code ai.djl} at run time
 * (native-image.properties), since their static initialisers load the libraries.
 * <p>
 * Registered from the jars on the classpath, like {@link AnthropicSdkRuntimeHints}; absent jars
 * register nothing.
 */
public class TransformersRuntimeHints implements RuntimeHintsRegistrar {

    /** Marker resource of each jar and the package prefixes to register from it. */
    private static final String[][] JARS = {
            {"ai/onnxruntime/OrtSession.class", "ai/onnxruntime/"},
            {"ai/djl/huggingface/tokenizers/HuggingFaceTokenizer.class", "ai/djl/huggingface/tokenizers/", "ai/djl/engine/rust/"},
            {"ai/djl/util/Platform.class", "ai/djl/util/", "ai/djl/engine/", "ai/djl/ndarray/"},
    };

    /**
     * Glob patterns, not regexes: {@code registerPattern} is written straight into the
     * {@code "glob"} entries of reachability-metadata.json, where {@code *} matches a path
     * segment and {@code .} is a literal dot. A regex {@code .*} therefore matches only names
     * that start with a dot — it silently embedded none of the {@code .so} files, and the image
     * died at first use with "Can't load library: onnxruntime".
     */
    private static final List<String> RESOURCE_PATTERNS = List.of(
            "ai/onnxruntime/native/linux-x64/*",
            "ai/onnxruntime/native/linux-aarch64/*",
            "native/lib/tokenizers.properties",
            "native/lib/linux-x86_64/cpu/*",
            "native/lib/linux-aarch64/cpu/*",
            "native/lib/*.properties");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (String[] jar : JARS) {
            URL url = classLoader.getResource(jar[0]);
            if (url == null || !"jar".equals(url.getProtocol())) {
                continue;
            }
            try {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                try (JarFile jarFile = connection.getJarFile()) {
                    registerClasses(jarFile, jar, hints);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan " + jar[0] + " for native image hints", e);
            }
        }
        RESOURCE_PATTERNS.forEach(pattern -> hints.resources().registerPattern(pattern));
    }

    private static void registerClasses(JarFile jarFile, String[] jar, RuntimeHints hints) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".class") || name.endsWith("module-info.class")) continue;
            boolean wanted = false;
            for (int i = 1; i < jar.length; i++) {
                if (name.startsWith(jar[i])) wanted = true;
            }
            if (!wanted) continue;
            TypeReference type = TypeReference.of(name.replace('/', '.').substring(0, name.length() - ".class".length()));
            hints.reflection().registerType(type, MemberCategory.values());
            hints.jni().registerType(type, MemberCategory.values());
        }
    }
}
