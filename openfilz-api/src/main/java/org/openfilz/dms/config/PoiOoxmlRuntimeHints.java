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
 * Registers GraalVM native image reflection hints for Apache POI OOXML schema classes.
 *
 * XMLBeans uses Class.forName() to load implementation classes (e.g.,
 * org.openxmlformats.schemas.wordprocessingml.x2006.main.impl.DocumentDocumentImpl)
 * from .xsb schema files at runtime. Without reflection registration, GraalVM native image
 * cannot resolve these classes and falls back to XmlComplexContentImpl, causing ClassCastExceptions.
 *
 * This registrar scans the poi-ooxml-lite JAR at AOT build time and registers all classes
 * under org.openxmlformats.schemas and com.microsoft.schemas for reflection.
 */
public class PoiOoxmlRuntimeHints implements RuntimeHintsRegistrar {

    private static final String[] SCHEMA_PACKAGES = {
            "org/openxmlformats/schemas/",
            "com/microsoft/schemas/",
            "org/etsi/uri/",
            "org/apache/poi/schemas/",
            "org/apache/xmlbeans/metadata/"
    };

    /**
     * Marker classes used to locate JARs to scan.
     * Each class lives in a different JAR (poi-ooxml-lite and xmlbeans).
     */
    private static final String[] JAR_MARKER_RESOURCES = {
            "org/apache/poi/schemas/ooxml/system/ooxml/TypeSystemHolder.class",
            "org/apache/xmlbeans/metadata/system/sXMLLANG/TypeSystemHolder.class"
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (String marker : JAR_MARKER_RESOURCES) {
            try {
                URL url = classLoader.getResource(marker);
                if (url == null) {
                    continue;
                }
                if ("jar".equals(url.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) url.openConnection();
                    try (JarFile jarFile = connection.getJarFile()) {
                        scanJar(jarFile, hints, classLoader);
                    }
                } else if ("file".equals(url.getProtocol())) {
                    scanClasspathPackages(hints, classLoader);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan schema classes for native image hints", e);
            }
        }
    }

    private void scanJar(JarFile jarFile, RuntimeHints hints, ClassLoader classLoader) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(".class") && isSchemaClass(name)) {
                String className = name.replace('/', '.').replace(".class", "");
                registerClass(className, hints, classLoader);
            }
        }
    }

    private void scanClasspathPackages(RuntimeHints hints, ClassLoader classLoader) {
        // Fallback: register the critical classes we know about
        // This path is mainly for tests; AOT processing typically uses JAR scanning above
        for (String pkg : SCHEMA_PACKAGES) {
            try {
                Enumeration<URL> resources = classLoader.getResources(pkg);
                while (resources.hasMoreElements()) {
                    URL resource = resources.nextElement();
                    if ("jar".equals(resource.getProtocol())) {
                        JarURLConnection connection = (JarURLConnection) resource.openConnection();
                        try (JarFile jarFile = connection.getJarFile()) {
                            scanJar(jarFile, hints, classLoader);
                        }
                    }
                }
            } catch (IOException e) {
                // Continue with other packages
            }
        }
    }

    private boolean isSchemaClass(String entryName) {
        for (String prefix : SCHEMA_PACKAGES) {
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
