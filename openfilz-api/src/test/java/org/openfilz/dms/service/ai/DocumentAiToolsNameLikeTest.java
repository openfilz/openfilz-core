package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model's name filter is matched as a partial, wildcard-escaped term: wildcards or quotes it adds
 * around the term must not turn into literal characters that match nothing.
 */
class DocumentAiToolsNameLikeTest {

    @Test
    void stripsWildcardsAndQuotesAroundTheTerm() {
        assertThat(DocumentAiTools.normalizeNameLike("%aiquery1234%")).isEqualTo("aiquery1234");
        assertThat(DocumentAiTools.normalizeNameLike("*invoice*")).isEqualTo("invoice");
        assertThat(DocumentAiTools.normalizeNameLike(" '%report 2026%' ")).isEqualTo("report 2026");
        assertThat(DocumentAiTools.normalizeNameLike("\"contract\"")).isEqualTo("contract");
    }

    @Test
    void keepsWhatIsInsideTheTerm() {
        assertThat(DocumentAiTools.normalizeNameLike("50%_discount")).isEqualTo("50%_discount");
        assertThat(DocumentAiTools.normalizeNameLike("plain.txt")).isEqualTo("plain.txt");
    }

    @Test
    void blankOrNullMeansNoFilter() {
        assertThat(DocumentAiTools.normalizeNameLike(null)).isNull();
        assertThat(DocumentAiTools.normalizeNameLike("  ")).isNull();
        assertThat(DocumentAiTools.normalizeNameLike("%%")).isNull();
        assertThat(DocumentAiTools.normalizeNameLike("null")).isNull();
    }
}
