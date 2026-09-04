package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batch defaults of {@link AiAccessPolicy}: an implementation that only knows
 * {@code canRead}/{@code canModify} gets correct {@code readable}/{@code modifiable} for free
 * (the enterprise policy overrides them with one query), and the permit-all core policy
 * short-circuits to the input.
 */
class AiAccessPolicyBatchDefaultsTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    /** Reads A and B, modifies only A; everything else is invisible. */
    private static final AiAccessPolicy SELECTIVE = new AiAccessPolicy() {
        @Override
        public boolean permitAll() {
            return false;
        }

        @Override
        public Mono<Boolean> canRead(UUID documentId, String userEmail) {
            return Mono.just(A.equals(documentId) || B.equals(documentId));
        }

        @Override
        public Mono<Boolean> canModify(UUID documentId, String userEmail) {
            return A.equals(documentId) ? Mono.just(true) : Mono.empty(); // empty = no verdict = denied
        }
    };

    @Test
    @DisplayName("the default readable/modifiable loop over the single-document verdicts")
    void defaultsLoopOverSingleVerdicts() {
        assertThat(SELECTIVE.readable(List.of(A, B, C, A), "alice").block()).containsExactly(A, B);
        assertThat(SELECTIVE.modifiable(List.of(A, B, C), "alice").block()).containsExactly(A);
    }

    @Test
    @DisplayName("an empty or null id list answers an empty set without consulting the policy")
    void emptyInputIsEmptyOutput() {
        assertThat(SELECTIVE.readable(List.of(), "alice").block()).isEmpty();
        assertThat(SELECTIVE.readable(null, "alice").block()).isEmpty();
        assertThat(SELECTIVE.modifiable(Set.of(), "alice").block()).isEmpty();
    }

    @Test
    @DisplayName("the permit-all core policy returns the input as is")
    void permitAllReturnsEverything() {
        PermitAllAiAccessPolicy policy = new PermitAllAiAccessPolicy();
        assertThat(policy.permitAll()).isTrue();
        assertThat(policy.readable(List.of(A, B, C), "anyone").block()).containsExactlyInAnyOrder(A, B, C);
        assertThat(policy.modifiable(List.of(A, C), "anyone").block()).containsExactlyInAnyOrder(A, C);
        assertThat(policy.modifiable(null, "anyone").block()).isEmpty();
    }
}
