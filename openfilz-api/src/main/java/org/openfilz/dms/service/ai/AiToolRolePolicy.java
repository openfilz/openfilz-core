package org.openfilz.dms.service.ai;

import org.springframework.security.core.Authentication;

/**
 * Decides whether a caller's OpenFilz roles allow a given {@link ToolCapability}.
 * <p>
 * This is the tool layer's equivalent of the role checks the HTTP security chain performs on REST
 * requests, and it exists because tools never pass through that chain: they run in-process on a
 * tool thread with no request to match. Both tool front-ends consult it — the in-app chat
 * assistant and the MCP server — so a role decision is made once and applies to both.
 * <p>
 * Deliberately separate from {@link AiAccessPolicy}: this answers <em>"may this user perform this
 * kind of operation?"</em>, while {@code AiAccessPolicy} answers <em>"may this user touch this
 * document?"</em>. A tool call must satisfy both, and neither is a substitute for the other.
 * <p>
 * The core implementation covers the roles core ships. The enterprise layer overrides this bean
 * {@code @Primary} to add its own — {@code VIEW_SHARE} / {@code EDIT_SHARE} for the share
 * capabilities, which Community refuses outright because it has no sharing model.
 *
 * @see ToolCapability
 * @see AiAccessPolicy
 */
public interface AiToolRolePolicy {

    /**
     * @param authentication the caller, as validated by the security chain. {@code null} means the
     *                       tools were built outside an authenticated front-end (the definitions-only
     *                       template instance, or a unit test); implementations must not treat that
     *                       as a user with no roles — see {@code DocumentAiTools} for why both real
     *                       front-ends always bind one.
     * @param capability     the kind of operation the tool is about to perform
     * @return whether the caller's roles permit it
     */
    boolean isAllowed(Authentication authentication, ToolCapability capability);
}
