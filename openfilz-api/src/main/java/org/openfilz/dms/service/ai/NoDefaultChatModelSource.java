package org.openfilz.dms.service.ai;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Core default: no runtime chat model, the deployment default (if any) applies. */
@Component
public class NoDefaultChatModelSource implements DefaultChatModelSource {

    @Override
    public Optional<UserChatClientResolver.ResolvedChat> current() {
        return Optional.empty();
    }

    @Override
    public long version() {
        return 0L;
    }
}
