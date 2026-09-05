package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.AiDocumentInsight;

import static org.assertj.core.api.Assertions.assertThat;

/** The states after which smart filing stops waiting for the insight row. */
class AutoFileSettledTest {

    @Test
    void noRowOrPendingIsNotSettled() {
        assertThat(DefaultAutoFileService.settled(null)).isFalse();
        assertThat(DefaultAutoFileService.settled(row(AiDocumentInsight.STATUS_PENDING, 2))).isFalse();
    }

    @Test
    void tierOneDoneIsNotSettled() {
        // Tika saved its metadata, the model has not answered yet: keep waiting
        assertThat(DefaultAutoFileService.settled(row(AiDocumentInsight.STATUS_DONE, 1))).isFalse();
        assertThat(DefaultAutoFileService.settled(row(AiDocumentInsight.STATUS_DONE, null))).isFalse();
    }

    @Test
    void tierTwoDoneIsSettled() {
        assertThat(DefaultAutoFileService.settled(row(AiDocumentInsight.STATUS_DONE, 2))).isTrue();
    }

    @Test
    void failedAndSkippedAreSettledWhateverTheTier() {
        assertThat(DefaultAutoFileService.settled(row(AiDocumentInsight.STATUS_FAILED, 1))).isTrue();
        assertThat(DefaultAutoFileService.settled(row(AiDocumentInsight.STATUS_SKIPPED, null))).isTrue();
    }

    private static AiDocumentInsight row(String status, Integer tier) {
        return AiDocumentInsight.builder().status(status).tier(tier).build();
    }
}
