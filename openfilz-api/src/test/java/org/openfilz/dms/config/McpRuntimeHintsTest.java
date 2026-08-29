package org.openfilz.dms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 0 of the MCP native-image test strategy (see
 * {@code openfilz-enterprise/docs/private/mcp-server-plan.md}).
 * <p>
 * Only the enterprise {@code collaboration} module builds a GraalVM native image, so a missing
 * reflection hint cannot fail a core build — it surfaces as a runtime error on the first
 * {@code tools/list} of an EE native deployment, weeks later. These assertions move that
 * failure forward to a plain unit test that runs on any machine in milliseconds.
 * <p>
 * Two distinct things are asserted:
 * <ol>
 *   <li><b>Upstream coverage.</b> {@code spring-ai-mcp} registers
 *       {@code org.springframework.ai.mcp.aot.McpHints} through {@code META-INF/spring/aot.factories},
 *       which covers the {@code McpSchema} protocol types. We depend on that instead of
 *       duplicating it — so this test fails loudly if a Spring AI upgrade ever drops it.</li>
 *   <li><b>Our own hints.</b> {@link McpRuntimeHints} covers what upstream does not: the
 *       ServiceLoader-resolved JSON layer.</li>
 * </ol>
 */
class McpRuntimeHintsTest {

    /**
     * Protocol types Jackson (de)serializes on every MCP request.
     * <p>
     * Note these are the <em>nested</em> types: {@code McpHints} registers
     * {@code AiRuntimeHints.findInnerClassesFor(McpSchema.class)}, not the {@code McpSchema}
     * holder itself — the holder carries no serialized state, its inner records carry all of it.
     * Listing the handshake ({@code Initialize*}), the tool surface ({@code Tool},
     * {@code CallTool*}, {@code ListToolsResult}), the envelope ({@code JSONRPC*}) and the
     * payload ({@code TextContent}) covers every type a {@code tools/list} + {@code tools/call}
     * round trip touches.
     */
    private static final List<String> PROTOCOL_TYPES = List.of(
            "io.modelcontextprotocol.spec.McpSchema$InitializeRequest",
            "io.modelcontextprotocol.spec.McpSchema$InitializeResult",
            "io.modelcontextprotocol.spec.McpSchema$JSONRPCRequest",
            "io.modelcontextprotocol.spec.McpSchema$JSONRPCResponse",
            "io.modelcontextprotocol.spec.McpSchema$Tool",
            "io.modelcontextprotocol.spec.McpSchema$ListToolsResult",
            "io.modelcontextprotocol.spec.McpSchema$CallToolRequest",
            "io.modelcontextprotocol.spec.McpSchema$CallToolResult",
            "io.modelcontextprotocol.spec.McpSchema$TextContent");

    private static RuntimeHints hintsFrom(RuntimeHintsRegistrar registrar) {
        RuntimeHints hints = new RuntimeHints();
        registrar.registerHints(hints, McpRuntimeHintsTest.class.getClassLoader());
        return hints;
    }

    @Test
    @DisplayName("Spring AI still registers the MCP protocol hints via aot.factories")
    void upstreamRegistrarCoversProtocolSchema() {
        RuntimeHints hints = new RuntimeHints();
        List<RuntimeHintsRegistrar> registrars = SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class);

        assertThat(registrars)
                .as("no RuntimeHintsRegistrar found on the classpath at all — aot.factories lookup broken")
                .isNotEmpty();

        registrars.forEach(registrar -> registrar.registerHints(hints, getClass().getClassLoader()));

        PROTOCOL_TYPES.forEach(type -> assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(type)))
                .as("""
                        %s is not reflection-registered by any classpath RuntimeHintsRegistrar. \
                        Spring AI used to cover it through spring-ai-mcp's McpHints — if that was \
                        dropped or renamed upstream, register it in McpRuntimeHints instead.""", type)
                .accepts(hints));
    }

    @Test
    @DisplayName("MCP ServiceLoader SPI files are registered as resources")
    void registersServiceLoaderResources() {
        RuntimeHints hints = hintsFrom(new McpRuntimeHints());

        McpRuntimeHints.SERVICE_RESOURCES.forEach(resource ->
                assertThat(RuntimeHintsPredicates.resource().forResource(resource))
                        .as("%s must be a registered resource: McpJsonMapper and JsonSchemaValidator "
                                + "are resolved through ServiceLoader, which reads it at runtime", resource)
                        .accepts(hints));
    }

    @Test
    @DisplayName("MCP ServiceLoader implementations are reflection-registered")
    void registersServiceLoaderImplementations() {
        RuntimeHints hints = hintsFrom(new McpRuntimeHints());

        McpRuntimeHints.SERVICE_IMPLEMENTATIONS.forEach(className ->
                assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(className)))
                        .as("%s must be reflection-registered so ServiceLoader can instantiate it", className)
                        .accepts(hints));
    }

    @Test
    @DisplayName("the jackson3 SPI implementations we hint actually exist on the classpath")
    void hintedImplementationsExist() {
        // Guards against silently hinting stale class names: the MCP SDK moved from a
        // jackson2-flavoured artifact to mcp-json-jackson3, and hints for classes that no longer
        // exist are dead weight that still reads as "covered".
        McpRuntimeHints.SERVICE_IMPLEMENTATIONS.forEach(className ->
                assertThatClassExists(className));
    }

    private static void assertThatClassExists(String className) {
        try {
            Class.forName(className, false, McpRuntimeHintsTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(
                    "McpRuntimeHints references " + className + ", which is not on the classpath. "
                            + "The MCP SDK's JSON layer was probably renamed — update the hints.", e);
        }
    }
}
