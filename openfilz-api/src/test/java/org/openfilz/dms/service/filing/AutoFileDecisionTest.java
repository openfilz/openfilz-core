package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.filing.AutoFileDecision.ModelAnswer;
import org.openfilz.dms.service.filing.AutoFileDecision.Neighbour;
import org.openfilz.dms.service.filing.AutoFileDecision.Vote;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
    @DisplayName("only neighbours of the document's category vote; those of an unknown category still do")
    void sameCategoryOnly() {
        List<Neighbour> neighbours = List.of(
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.95, "report"),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.94, "report"),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.93, "report"),
                new Neighbour(UUID.randomUUID(), INVOICES, 0.90, "invoice"),
                new Neighbour(UUID.randomUUID(), INVOICES, 0.89, null));
        Optional<Vote> vote = AutoFileDecision.vote(neighbours, "invoice", 0.6, 0.5, 0);
        assertThat(vote).isPresent();
        assertThat(vote.get().folderId()).isEqualTo(INVOICES);
        assertThat(vote.get().documents()).isEqualTo(2);
        // Without a category on the document, the reports outvote the invoices on headcount as before
        assertThat(AutoFileDecision.vote(neighbours, null, 0.6, 0.5, 0)).get().extracting(Vote::folderId).isEqualTo(CONTRACTS);
    }

    @Test
    @DisplayName("neighbours far below the best hit do not vote")
    void relativeSimilarityDropsTheTail() {
        List<Neighbour> neighbours = List.of(
                new Neighbour(UUID.randomUUID(), INVOICES, 0.92),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.62),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.61),
                new Neighbour(UUID.randomUUID(), CONTRACTS, 0.60));
        assertThat(AutoFileDecision.vote(neighbours, null, 0.6, 0.5, 0)).as("headcount wins without the guard")
                .get().extracting(Vote::folderId).isEqualTo(CONTRACTS);
        Optional<Vote> vote = AutoFileDecision.vote(neighbours, null, 0.6, 0.5, 0.85);
        assertThat(vote).isPresent();
        assertThat(vote.get().folderId()).isEqualTo(INVOICES);
        assertThat(vote.get().documents()).isEqualTo(1);
    }

    @Test
    @DisplayName("similarity purity: the share of a folder's files close to the document, no category needed")
    void similarityPurity() {
        // An invoice seen from a folder of 3 invoices and 2 reports: two clusters, a gap of 0.33 between them
        List<Double> mixed = List.of(0.92, 0.90, 0.88, 0.55, 0.50);
        assertThat(AutoFileDecision.similarityPurity(mixed, 0.1)).isCloseTo(0.6, within(0.001));
        // Same-kind documents spread out without a gap: one cluster, a home
        assertThat(AutoFileDecision.similarityPurity(List.of(0.90, 0.84, 0.79, 0.73, 0.66), 0.1)).isEqualTo(1.0);
        assertThat(AutoFileDecision.similarityPurity(List.of(), 0.1)).isNaN();
        assertThat(AutoFileDecision.coherentBySimilarity(mixed, 0.1, 0.7, 3)).as("a grab-bag").isFalse();
        assertThat(AutoFileDecision.coherentBySimilarity(List.of(0.9, 0.85, 0.8), 0.1, 0.7, 3)).as("a home").isTrue();
        assertThat(AutoFileDecision.coherentBySimilarity(List.of(0.9, 0.4), 0.1, 0.7, 3)).as("too young to judge").isTrue();
        assertThat(AutoFileDecision.coherentBySimilarity(null, 0.1, 0.7, 3)).isTrue();
    }

    @Test
    @DisplayName("the fit: a small tight folder of the document's kind beats a large mixed one")
    void fit() {
        UUID tight = UUID.randomUUID();
        UUID mixed = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID lone = UUID.randomUUID();
        Map<UUID, List<Double>> candidates = Map.of(
                tight, List.of(0.90, 0.88, 0.87),
                mixed, List.of(0.93, 0.92, 0.91, 0.60, 0.58, 0.55, 0.50, 0.45),
                far, List.of(0.40, 0.38, 0.35),
                lone, List.of(0.99));
        Optional<AutoFileDecision.FolderFit> best = AutoFileDecision.fit(candidates, 0.1, 0.7, 0.5, 3);
        assertThat(best).isPresent();
        assertThat(best.get().folderId()).as("a single member is trivially pure: not a candidate").isEqualTo(tight);
        assertThat(best.get().purity()).isEqualTo(1.0);
        assertThat(best.get().members()).isEqualTo(3);
        assertThat(best.get().score()).isCloseTo(0.8833, within(0.001));
        // Nothing coherent and close: no stage-1 answer
        assertThat(AutoFileDecision.fit(Map.of(mixed, candidates.get(mixed), far, candidates.get(far)), 0.1, 0.7, 0.5, 3)).isEmpty();
        assertThat(AutoFileDecision.fit(Map.of(), 0.1, 0.7, 0.5, 3)).isEmpty();
    }

    @Test
    @DisplayName("a folder is a home when its dominant category is the document's and holds most of its files")
    void coherence() {
        assertThat(AutoFileDecision.coherent(Map.of("invoice", 9, "report", 1), "invoice", 0.7)).isTrue();
        assertThat(AutoFileDecision.coherent(Map.of("invoice", 5, "report", 5), "invoice", 0.7)).as("mixed").isFalse();
        assertThat(AutoFileDecision.coherent(Map.of("report", 9, "invoice", 1), "invoice", 0.7)).as("another kind's home").isFalse();
        assertThat(AutoFileDecision.coherent(Map.of(), "invoice", 0.7)).as("nothing known about the folder").isTrue();
        assertThat(AutoFileDecision.coherent(Map.of("report", 9), null, 0.7)).as("nothing known about the document").isTrue();
        assertThat(AutoFileDecision.coherent(Map.of("Invoice", 3), "invoice", 0.7)).as("case-insensitive").isTrue();
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
