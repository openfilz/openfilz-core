package org.openfilz.dms.service.mcp;

import org.openfilz.dms.service.ai.ToolCapability;
import org.springframework.security.core.Authentication;

import java.util.Map;

/**
 * A source of MCP tools. Every {@code @Tool}-annotated object exposed over {@code /mcp} comes from
 * a contributor, and {@link McpToolCallbackProvider} applies the same wrapping — authentication,
 * role enforcement, read-only filtering, per-call user binding — to all of them uniformly.
 * <p>
 * This is the seam the enterprise edition extends: core registers a contributor for
 * {@code DocumentAiTools}, and {@code collaboration} registers a second one for its share / comment
 * / e-Sign tools. The enterprise tools then inherit all of the enforcement for free, and adding
 * them needs <b>no change to core tool logic</b> — only a new bean implementing this interface.
 * <p>
 * Spring AI would also merge a second {@code ToolCallbackProvider} bean, but that route would force
 * each edition to re-implement the user binding and the role/read-only gates. Routing every tool
 * through the single {@code McpToolCallbackProvider} keeps one implementation of the security
 * behaviour and one place a tool can be refused.
 *
 * @see DocumentAiToolsContributor the core implementation
 */
public interface McpToolContributor {

    /**
     * A tool object (or objects) carrying {@code @Tool} methods, bound to the calling user.
     * <p>
     * Called two ways:
     * <ul>
     *   <li><b>Definitions template</b> — {@code bind(null, null)} at {@code tools/list} time. The
     *       returned object's tool <em>definitions</em> (name, description, JSON schema) are
     *       harvested; it is never executed, so it must build without touching a per-request
     *       resource (notably: resolve no {@code ChatModel} here — that closes a startup bean
     *       cycle).</li>
     *   <li><b>Bound execution</b> — {@code bind(email, authentication)} per {@code tools/call},
     *       with the authenticated caller, so the access policy and secure DAO overrides see the
     *       right user.</li>
     * </ul>
     *
     * @param userEmail      the caller's email, or {@code null} for the definitions template
     * @param authentication the caller, or {@code null} for the definitions template
     * @return a single tool object, or an array/collection of them (whatever
     *         {@code MethodToolCallbackProvider.builder().toolObjects(...)} accepts)
     */
    Object bind(String userEmail, Authentication authentication);

    /**
     * The capability every tool this contributor exposes requires — the source of truth for both
     * the role gate and the read-only classification. A tool absent from this map is refused
     * rather than run (fail closed), so a tool added without a capability cannot ship.
     */
    Map<String, ToolCapability> capabilities();
}
