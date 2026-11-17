package org.openfilz.dms.service.ai;

import java.util.Optional;

/**
 * A chat model set at runtime for every user of the deployment, consulted by
 * {@link UserChatClientResolver} after the user's personal settings and before the deployment
 * default ({@code OPENFILZ_AI_MODEL}, provider switches, fallback chain).
 * <p>
 * {@link #current()} is called on every chat request and by {@code GET /settings}: it must answer
 * from memory, never from the database. {@link #version()} moves whenever the model behind
 * {@link #current()} changes (set, changed or removed), so callers that keep a model for a long
 * time (the background insight and filing workers) know when to resolve again.
 * <p>
 * The core default ({@link NoDefaultChatModelSource}) has none. An extension provides its own
 * implementation as a {@code @Primary} bean.
 */
public interface DefaultChatModelSource {

    /** The model in force, or empty when none is set. Answers from memory. */
    Optional<UserChatClientResolver.ResolvedChat> current();

    /** Changes every time {@link #current()} would answer differently. */
    long version();
}
