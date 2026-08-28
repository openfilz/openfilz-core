package org.openfilz.dms.service.ai;

/**
 * What a tool needs permission to <em>do</em>, independently of which documents it may touch.
 * <p>
 * OpenFilz authorises in two orthogonal layers, and a tool call must pass both:
 * <ol>
 *   <li><b>Capability</b> (this enum, resolved by {@link AiToolRolePolicy}) — may this caller
 *       perform this <em>kind</em> of operation at all? Backed by the OpenFilz role model, and
 *       identical in Community and Enterprise.</li>
 *   <li><b>Document scope</b> ({@link AiAccessPolicy}) — may this caller touch <em>this</em>
 *       document? Permit-all in Community; ownership- and share-backed in Enterprise.</li>
 * </ol>
 * Neither substitutes for the other: the capability layer stops a READER from writing anything at
 * all, while the scope layer stops a CONTRIBUTOR from writing to someone else's document.
 * <p>
 * <b>Why this exists.</b> The role model used to be enforced only by the HTTP security chain
 * ({@code DefaultAuthSecurityConfig}), which maps request method + path to a role. Tools bypass
 * that entirely — they call {@code DocumentService} in-process on a tool thread, with no request
 * being matched — so a READER could create folders through {@code /mcp} (and through the chat
 * assistant) that the very same token was refused over REST.
 * <p>
 * Capabilities are declared here even when no tool exposes them yet, so the mapping is settled
 * once and a tool added later cannot quietly ship unclassified — {@code DocumentAiTools} and
 * {@code McpToolCallbackProvider} both fail closed on an unmapped tool.
 */
public enum ToolCapability {

    /** Read or search documents. Mirrors the REST read/search endpoints: READER or CONTRIBUTOR. */
    DOCUMENT_READ,

    /** Create or modify documents and folders. Mirrors REST insert/update: CONTRIBUTOR. */
    DOCUMENT_WRITE,

    /** Delete or trash documents. Mirrors REST delete: CLEANER. No tool exposes this yet. */
    DOCUMENT_DELETE,

    /** Read or search the audit trail. Mirrors {@code /api/v1/audit}: AUDITOR. No tool yet. */
    AUDIT_READ,

    /**
     * Initiate e-Sign requests (envelopes, templates). Mirrors the REST e-Sign writes:
     * CONTRIBUTOR, and additionally SIGN_REQUESTER when
     * {@code openfilz.signature.require-requester-role} is on. No tool exposes this yet.
     */
    SIGNATURE_WRITE,

    /**
     * Read sharing information on the caller's documents. <b>Enterprise only</b> (VIEW_SHARE) —
     * Community has no sharing model, so the core policy refuses it. No tool exposes this yet.
     */
    SHARE_READ,

    /**
     * Create or change shares on the caller's documents. <b>Enterprise only</b> (EDIT_SHARE) —
     * refused by the core policy. No tool exposes this yet.
     */
    SHARE_WRITE
}
