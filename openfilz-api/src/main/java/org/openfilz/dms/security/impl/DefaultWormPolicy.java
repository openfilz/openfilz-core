package org.openfilz.dms.security.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.security.WormPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Deployment-wide WORM perimeter, driven by {@code openfilz.security.worm-mode}: when on, the
 * whole repository is write-once; when off, nothing is.
 * <p>
 * Also carries the start-up guards for the mode, which is why they run here rather than in a
 * security service: a misconfigured perimeter must fail the boot, whatever edition is wired above
 * it. {@code openfilz.features.custom-access} is pointedly <em>not</em> among them any more — that
 * combination is now supported (D1).
 */
@Slf4j
@Service
public class DefaultWormPolicy implements WormPolicy {

    @Value("${openfilz.security.worm-mode:false}")
    private boolean wormMode;

    @Value("${openfilz.security.no-auth}")
    private boolean noAuth;

    @Value("${openfilz.calculate-checksum:false}")
    private boolean calculateChecksum;

    @PostConstruct
    public void init() {
        if (!wormMode) {
            return;
        }
        if (noAuth) {
            throw new IllegalStateException("Bad configuration : when openfilz.security.no-auth is true, "
                    + "openfilz.security.worm-mode must be false");
        }
        // C1 — a write-once perimeter without a content fingerprint proves nothing: there would be
        // no way to show afterwards that what was written is what is being read back.
        if (!calculateChecksum) {
            throw new IllegalStateException("Bad configuration : when openfilz.security.worm-mode is true, "
                    + "openfilz.calculate-checksum must be true");
        }
        log.info("WORM mode active — the repository is write-once: no delete, no in-place update.");
    }

    @Override
    public boolean isEnforced() {
        return wormMode;
    }

    @Override
    public boolean covers(HttpMethod method, String apiPath) {
        return wormMode;
    }
}
