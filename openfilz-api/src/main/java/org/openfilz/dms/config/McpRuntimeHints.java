package org.openfilz.dms.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.List;

/**
 * GraalVM native image hints for the MCP server that are <em>not</em> already covered upstream.
 * <p>
 * Spring AI 2.0 ships {@code org.springframework.ai.mcp.aot.McpHints}, registered through
 * {@code META-INF/spring/aot.factories} in {@code spring-ai-mcp}, which reflection-registers
 * {@code io.modelcontextprotocol.spec.McpSchema} and its nested protocol types. That was the
 * bulk of the feared native surface, and the framework already handles it — do not duplicate it
 * here.
 * <p>
 * What remains is the SDK's pluggable JSON layer, which is resolved through
 * {@link java.util.ServiceLoader}: {@code McpJsonMapper.getDefault()} and
 * {@code JsonSchemaValidator} look their implementation up by service file, and native-image
 * drops both the service resource and the implementation's constructor as unreachable.
 * <p>
 * Only the CE build is JVM; the enterprise {@code collaboration} module compiles these same
 * classes into a native image, which is why this lives in core next to the dependency it
 * describes even though core never builds native.
 *
 * @see AnthropicSdkRuntimeHints
 */
public class McpRuntimeHints implements RuntimeHintsRegistrar {

    /**
     * ServiceLoader SPI files the MCP SDK reads at runtime. Registered as resources so the
     * lookup finds them, and the implementations below so the loader can instantiate them.
     */
    static final List<String> SERVICE_RESOURCES = List.of(
            "META-INF/services/io.modelcontextprotocol.json.McpJsonMapperSupplier",
            "META-INF/services/io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier");

    /** Jackson 3 implementations of the SPIs above (the flavour Spring Boot 4 uses). */
    static final List<String> SERVICE_IMPLEMENTATIONS = List.of(
            "io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier",
            "io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper",
            "io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier",
            "io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        SERVICE_RESOURCES.forEach(resource -> hints.resources().registerPattern(resource));
        SERVICE_IMPLEMENTATIONS.stream()
                .filter(className -> isPresent(className, classLoader))
                .forEach(className -> hints.reflection().registerType(TypeReference.of(className),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS));
    }

    /** The MCP starter is optional on the classpath — register nothing rather than fail AOT. */
    private static boolean isPresent(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }
}
