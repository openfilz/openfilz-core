package org.openfilz.dms.service.insight;

import org.springframework.stereotype.Service;

/**
 * The core policy: every document may be enriched by the configured model, smart filing is
 * open to every user. Exists so the worker always has a policy to ask; an extension registers a
 * {@code @Primary} {@link InsightsPolicy} to narrow it. Always a bean (never a bean condition):
 * the governance rules are a runtime matter, and native images resolve conditions at build time.
 */
@Service
public class PermitAllInsightsPolicy implements InsightsPolicy {
}
