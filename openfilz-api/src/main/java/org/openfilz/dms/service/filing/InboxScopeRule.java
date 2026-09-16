package org.openfilz.dms.service.filing;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Inbox convention (design §13.5) as a filing rule, the first to run: a document lying in the
 * caller's Inbox is filed with the <em>whole library</em> as scope — dropping it there meant "I
 * don't want to think about where this goes" — and the Inbox is never a destination, wherever
 * the document came from. It names no target, so the vote, the rule-by-kind and the model decide
 * as usual; a document they leave SKIPPED simply stays in the Inbox, which is what an Inbox is for.
 * Silent when the deployment does not offer the Inbox or the caller has none.
 */
@Slf4j
@Service
@Lazy
public class InboxScopeRule implements DestinationRule {

    public static final String RULE_ID = "inbox";
    /** Well before anything an extension registers: the scope must be settled first. */
    public static final int ORDER = -1000;

    private final AiPreferencesService preferences;

    public InboxScopeRule(AiPreferencesService preferences) {
        this.preferences = preferences;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Optional<Decision> decide(FilingContext context) {
        String email = context.caller() == null || context.caller().email() == null || context.caller().email().isBlank()
                ? UserInfoService.ANONYMOUS_USER : context.caller().email();
        UUID inbox;
        try {
            inbox = preferences.inboxFolderId(email).block();
        } catch (Exception e) {
            log.debug("[AUTOFILE] inbox lookup failed for {}: {}", email, e.getMessage());
            return Optional.empty();
        }
        if (inbox == null) {
            return Optional.empty();
        }
        if (Objects.equals(context.document().getParentId(), inbox)) {
            return Optional.of(Decision.scope(null, RULE_ID, "dropped in the Inbox: the whole library is the scope", Set.of(inbox)));
        }
        return Optional.of(Decision.exclude(RULE_ID, "the Inbox is never a destination", Set.of(inbox)));
    }
}
