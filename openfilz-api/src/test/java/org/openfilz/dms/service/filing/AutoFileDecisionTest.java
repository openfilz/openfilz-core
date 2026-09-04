package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.filing.AutoFileDecision.ModelAnswer;
import org.openfilz.dms.service.filing.AutoFileDecision.Neighbour;
import org.openfilz.dms.service.filing.AutoFileDecision.Vote;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The smart-filing decisions: the neighbour vote thresholds and the model-answer contract. */
class AutoFileDecisionTest {

    private static final UUID INVOICES = UUID.randomUUID();
    private static final UUID CONTRACTS = UUID.randomUUID();

    @Test
    @DisplayName("the folder holding most of the similarity weight wins when share and similarity clear the bars")
    void leadingFolderWins() {
        List<Neighbour> neighbours = List.of(
                new Neighbour(UUID.randomUUID(), INVOICES, 0.92),
                new Neighbour(UUID.randomUUID(), INVOICES, 0.88),
                new Neighbour(UUID.randomUUID(), INVOICES, 0.80),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.70));

        Optional<Vote> vote = AutoFileDecision.vote(neighbours, 0.6, 0.5);

        assertThat(vote).isPresent();
        assertThat(vote.get().folderId()).isEqualTo(INVOICES);
        assertThat(vote.get().share()).isGreaterThan(0.75);
        assertThat(vote.get().bestSimilarity()).isEqualTo(0.92);
        assertThat(vote.get().documents()).isEqualTo(3);
    }

    @Test
    @DisplayName("a split vote or a weak best similarity decides nothing")
    void weakVotesDecideNothing() {
        List<Neighbour> split = List.of(
                new Neighbour(UUID.randomUUID(), INVOICES, 0.9),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.9));
        assertThat(AutoFileDecision.vote(split, 0.6, 0.5)).isEmpty();

        List<Neighbour> weak = List.of(new Neighbour(UUID.randomUUID(), INVOICES, 0.3));
        assertThat(AutoFileDecision.vote(weak, 0.6, 0.5)).isEmpty();

        assertThat(AutoFileDecision.vote(List.of(), 0.6, 0.5)).isEmpty();
        assertThat(AutoFileDecision.vote(null, 0.6, 0.5)).isEmpty();
    }

    @Test
    @DisplayName("the model answer is parsed leniently and its confidence clamped")
    void parsesModelAnswer() {
        ModelAnswer answer = ModelAnswer.parse("""
                Sure! ```json
                {"target": "Finance/Invoices/2026", "createFolders": ["Finance/Invoices/2026"], "confidence": "0.91", "reason": "Invoice from ACME dated 2026"}
                ```""");
        assertThat(answer.target()).isEqualTo("Finance/Invoices/2026");
        assertThat(answer.createFolders()).containsExactly("Finance/Invoices/2026");
        assertThat(answer.confidence()).isEqualTo(0.91);
        assertThat(answer.reason()).contains("ACME");

        assertThat(ModelAnswer.parse("{\"target\":\"\",\"confidence\":7}").confidence()).isEqualTo(1.0);
        assertThatThrownBy(() -> ModelAnswer.parse("no idea")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelAnswer.parse("{\"confidence\":0.9}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void depthCountsSegments() {
        assertThat(AutoFileDecision.depthOf("Finance/Invoices/2026")).isEqualTo(3);
        assertThat(AutoFileDecision.depthOf("/Finance/")).isEqualTo(1);
        assertThat(AutoFileDecision.depthOf("")).isZero();
        assertThat(AutoFileDecision.depthOf(null)).isZero();
    }
}
