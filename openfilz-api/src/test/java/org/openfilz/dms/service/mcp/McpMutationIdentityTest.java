package org.openfilz.dms.service.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.ai.ToolCapability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openfilz.dms.service.mcp.McpToolCallbackProvider.mutationRequiresIdentity;

/**
 * A mutating MCP tool must run as a real, identifiable user. A token with no {@code email} claim —
 * a bare service account — must be refused on writes, or its effects would be attributed to
 * {@code ANONYMOUS_USER} in the audit chain and scoped against a null user. Reads are left to the
 * access policy.
 * <p>
 * This is the unit half of the identity guard; the role gate already refuses a bare service account
 * that also lacks the write role, so the two layers compose.
 *
 * @see McpToolCallbackProvider#mutationRequiresIdentity
 */
class McpMutationIdentityTest {

    @Test
    @DisplayName("a write with no user identity (email) is refused; a real email proceeds")
    void writeWithoutIdentityIsRefused() {
        for (ToolCapability capability : ToolCapability.values()) {
            if (!capability.isMutating()) {
                continue;
            }
            assertThat(mutationRequiresIdentity(capability, null))
                    .as("%s with a null email must be refused", capability).isNotNull();
            assertThat(mutationRequiresIdentity(capability, "  "))
                    .as("%s with a blank email must be refused", capability).isNotNull();
            assertThat(mutationRequiresIdentity(capability, "agent@acme.com"))
                    .as("%s with a real email must proceed", capability).isNull();
        }
    }

    @Test
    @DisplayName("a read is never blocked by the identity guard, even with no email")
    void readIsNeverBlockedHere() {
        for (ToolCapability capability : ToolCapability.values()) {
            if (capability.isMutating()) {
                continue;
            }
            assertThat(mutationRequiresIdentity(capability, null))
                    .as("%s (read) must not be blocked by the mutation-identity guard", capability)
                    .isNull();
        }
    }
}
