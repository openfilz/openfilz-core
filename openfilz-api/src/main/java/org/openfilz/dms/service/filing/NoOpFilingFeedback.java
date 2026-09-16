package org.openfilz.dms.service.filing;

import org.springframework.stereotype.Service;

/**
 * The core default: no weighting, corrections are not recorded. An extension registers a
 * {@code @Primary} {@link FilingFeedback} to replace it.
 */
@Service
public class NoOpFilingFeedback implements FilingFeedback {
}
