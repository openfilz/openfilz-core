package org.openfilz.dms.service.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRangeParserTest {

    @Test
    void nullOrBlankMeansEveryPage() {
        assertThat(PageRangeParser.parse(null, 3)).containsExactly(1, 2, 3);
        assertThat(PageRangeParser.parse("  ", 2)).containsExactly(1, 2);
        assertThat(PageRangeParser.parse("all", 2)).containsExactly(1, 2);
    }

    @Test
    void singlePagesRangesAndOpenEnds() {
        assertThat(PageRangeParser.parse("3", 5)).containsExactly(3);
        assertThat(PageRangeParser.parse("2-4", 5)).containsExactly(2, 3, 4);
        assertThat(PageRangeParser.parse("4-", 5)).containsExactly(4, 5);
        assertThat(PageRangeParser.parse("-2", 5)).containsExactly(1, 2);
        assertThat(PageRangeParser.parse("1-3,7,10-", 12)).containsExactly(1, 2, 3, 7, 10, 11, 12);
        assertThat(PageRangeParser.parse("1-3 7 10-", 12)).containsExactly(1, 2, 3, 7, 10, 11, 12);
    }

    @Test
    void oddAndEven() {
        assertThat(PageRangeParser.parse("odd", 5)).containsExactly(1, 3, 5);
        assertThat(PageRangeParser.parse("even", 5)).containsExactly(2, 4);
        assertThat(PageRangeParser.parse("EVEN", 4)).containsExactly(2, 4);
    }

    @Test
    void orderAndDuplicatesAreKept() {
        assertThat(PageRangeParser.parse("3,1,1", 3)).containsExactly(3, 1, 1);
    }

    @Test
    void rejectsOutOfRangeAndMalformed() {
        assertThatThrownBy(() -> PageRangeParser.parse("0", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> PageRangeParser.parse("4", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 pages");
        assertThatThrownBy(() -> PageRangeParser.parse("3-2", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start is after end");
        assertThatThrownBy(() -> PageRangeParser.parse("a-b", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageRangeParser.parse("1-2-3", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageRangeParser.parse(",", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty");
        assertThatThrownBy(() -> PageRangeParser.parse("1", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largeSelection() {
        List<Integer> pages = PageRangeParser.parse("1-", 500);
        assertThat(pages).hasSize(500).startsWith(1).endsWith(500);
    }
}
