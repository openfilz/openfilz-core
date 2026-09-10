package org.openfilz.dms.security;

import org.springframework.http.HttpMethod;

/**
 * WORM (write-once, read-many) perimeter seam.
 * <p>
 * This interface answers one question only — <em>is this request inside a write-once
 * perimeter?</em> — and deliberately not <em>what is forbidden there</em>. The prohibition itself
 * lives in {@code AbstractSecurityService}, so every edition inherits the same rule instead of
 * re-deriving it; a deployment that needs a narrower perimeter replaces this bean alone.
 * <p>
 * WORM used to be a whole {@code SecurityService} implementation of its own, mutually exclusive
 * with the Community and Enterprise ones — which is why it refused to start under
 * {@code openfilz.features.custom-access=true} and was therefore unreachable for every Enterprise
 * deployment. As a rule rather than a rival bean it composes with any edition.
 * <p>
 * The shipped {@code DefaultWormPolicy} is deployment-wide: the perimeter is either the whole
 * repository or nothing. A per-subtree perimeter (an archive space frozen while the collaborative
 * space stays live) is the next step and needs only this bean replaced — see
 * {@code docs/secufix-remaining-work.md} §D1 point 3.
 */
@FunctionalInterface
public interface WormPolicy {

    /**
     * Whether any WORM perimeter is configured at all. Cheap short-circuit, and the flag the
     * storage layer and the start-up guards read.
     */
    boolean isEnforced();

    /**
     * Whether the perimeter covers this particular request. The deployment-wide default answers
     * {@link #isEnforced()} for every request; a scoped implementation resolves the target
     * resource instead.
     *
     * @param method  the request method
     * @param apiPath the path relative to the API prefix (e.g. {@code /documents/upload})
     */
    default boolean covers(HttpMethod method, String apiPath) {
        return isEnforced();
    }
}
