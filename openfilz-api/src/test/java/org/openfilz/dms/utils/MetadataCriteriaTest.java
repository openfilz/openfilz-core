package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataCriteriaTest {

    @Test
    void wildcardsAndSingleCharacter_areTranslated() {
        assertEquals("beta%", MetadataCriteria.toLikePattern("beta%"));
        assertEquals("beta%", MetadataCriteria.toLikePattern("beta*"));
        assertEquals("%beta%", MetadataCriteria.toLikePattern("*beta%"));
        assertEquals("beta-_", MetadataCriteria.toLikePattern("beta-?"));
        assertEquals("%", MetadataCriteria.toLikePattern("%*%"), "runs of wildcards collapse");
    }

    @Test
    void underscoreAndBackslash_areLiteral() {
        assertEquals("INV\\_2024%", MetadataCriteria.toLikePattern("INV_2024%"));
        assertEquals("C:\\\\docs\\\\%", MetadataCriteria.toLikePattern("C:\\docs\\%"));
    }

    @Test
    void openSearchPattern_usesStarAndEscapesBackslash() {
        assertEquals("beta*", MetadataCriteria.toWildcardPattern("beta%"));
        assertEquals("*be?a*", MetadataCriteria.toWildcardPattern("%be?a*"));
        assertEquals("INV_2024*", MetadataCriteria.toWildcardPattern("INV_2024%"));
        assertEquals("C:\\\\d*", MetadataCriteria.toWildcardPattern("C:\\d%"));
        assertEquals("*be?a*", MetadataCriteria.toWildcardPattern("%*be?a**%"));
    }

    @Test
    void onlyStringsWithAWildcard_arePatterns() {
        assertTrue(MetadataCriteria.isPattern("a%"));
        assertTrue(MetadataCriteria.isPattern("*"));
        assertFalse(MetadataCriteria.isPattern("a?"), "? alone keeps an exact match");
        assertFalse(MetadataCriteria.isPattern("INV_2024"));
        assertFalse(MetadataCriteria.isPattern(2024));
        assertFalse(MetadataCriteria.isPattern(Map.of("a", "%")));
        assertFalse(MetadataCriteria.isPattern(null));
    }

    @Test
    void criteria_areSplitAndPatternsSortedByKey() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("zeta", "z%");
        input.put("status", "approved");
        input.put("alpha", "*");
        input.put("year", 2024);

        MetadataCriteria criteria = MetadataCriteria.of(input);

        assertEquals(Map.of("status", "approved", "year", 2024), criteria.exact());
        assertEquals(List.of(
                new MetadataCriteria.Pattern("alpha", "%", true),
                new MetadataCriteria.Pattern("zeta", "z%", false)), criteria.patterns());
    }

    @Test
    void limits_areEnforced() {
        Map<String, Object> tooMany = new HashMap<>();
        for (int i = 0; i <= MetadataCriteria.MAX_CRITERIA; i++) {
            tooMany.put("k" + i, "v");
        }
        assertThrows(IllegalArgumentException.class, () -> MetadataCriteria.validate(tooMany));
        assertThrows(IllegalArgumentException.class, () -> MetadataCriteria.validate(Map.of("k".repeat(101), "v")));
        assertThrows(IllegalArgumentException.class, () -> MetadataCriteria.validate(Map.of(" ", "v")));
        assertThrows(IllegalArgumentException.class, () -> MetadataCriteria.validate(Map.of("k", "%" + "x".repeat(200))));
        // a long exact value is not a pattern and is not limited
        assertDoesNotThrow(() -> MetadataCriteria.validate(Map.of("k", "x".repeat(500))));
        assertDoesNotThrow(() -> MetadataCriteria.validate(null));
    }
}
