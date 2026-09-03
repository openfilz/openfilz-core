package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReorganizationPlanMarkersTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void appendsOneMarkerPerPlanAfterABlankLine() {
        String text = ReorganizationPlanMarkers.append("Here is my proposal.", List.of(ID));
        assertThat(text).isEqualTo("Here is my proposal.\n\n[[reorg-plan:" + ID + "]]");
    }

    @Test
    void doesNotDuplicateAMarkerAlreadyPresent() {
        String once = ReorganizationPlanMarkers.append("Proposal", List.of(ID));
        String twice = ReorganizationPlanMarkers.append(once, List.of(ID));
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void appendsNothingWithoutPlans() {
        assertThat(ReorganizationPlanMarkers.append("text", List.of())).isEqualTo("text");
        assertThat(ReorganizationPlanMarkers.append("text", null)).isEqualTo("text");
    }

    @Test
    void stripRemovesMarkersAndTheirBlankLines() {
        String text = ReorganizationPlanMarkers.append("Proposal", List.of(ID, UUID.randomUUID()));
        assertThat(ReorganizationPlanMarkers.strip(text)).isEqualTo("Proposal");
        assertThat(ReorganizationPlanMarkers.strip("no markers here")).isEqualTo("no markers here");
        assertThat(ReorganizationPlanMarkers.strip(null)).isNull();
    }

    @Test
    void patternMatchesOnlyWellFormedMarkers() {
        assertThat(ReorganizationPlanMarkers.PATTERN.matcher("[[reorg-plan:" + ID + "]]").matches()).isTrue();
        assertThat(ReorganizationPlanMarkers.PATTERN.matcher("[[reorg-plan:not-a-uuid]]").matches()).isFalse();
        assertThat(ReorganizationPlanMarkers.PATTERN.matcher("[[doc:" + ID + ":root:FILE:x]]").find()).isFalse();
    }
}
