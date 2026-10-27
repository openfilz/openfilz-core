package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
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
        input.put("flag", "true");

        MetadataCriteria criteria = MetadataCriteria.of(input);

        assertEquals(Map.of("year", 2024), criteria.exact());
        assertEquals(List.of(
                new MetadataCriteria.Alternative("flag", List.of("true", true)),
                new MetadataCriteria.Alternative("status", List.of("approved"))), criteria.alternatives());
        assertEquals(List.of(
                new MetadataCriteria.Pattern("alpha", "%", true),
                new MetadataCriteria.Pattern("zeta", "z%", false)), criteria.patterns());
    }

    @Test
    void stringsSpellingABooleanNullOrNumber_areExpanded() {
        assertEquals(List.of("true", true), MetadataCriteria.spellings("true"));
        assertEquals(List.of("FALSE", false), MetadataCriteria.spellings("FALSE"));
        assertEquals(Arrays.asList("null", null), MetadataCriteria.spellings("null"));
        assertEquals(List.of("42", new BigDecimal("42")), MetadataCriteria.spellings("42"));
        assertEquals(List.of("-1.5", new BigDecimal("-1.5")), MetadataCriteria.spellings("-1.5"));
        assertEquals(List.of("1e3", new BigDecimal("1e3")), MetadataCriteria.spellings("1e3"));
        assertEquals(List.of("0", BigDecimal.ZERO), MetadataCriteria.spellings("0"));
    }

    @Test
    void stringsSpellingNothing_stayPlainStrings() {
        // 1e2147483648 / 1e-2147483649: BigDecimal would throw on the int-overflowing exponent
        for (String plain : List.of("007", "1.", "+1", "0x1", " 42", "42 ", "yes", "", "1e", ".5", "1e99999", "truee",
                "1e2147483648", "1e-2147483649", "1e99999999999999999999")) {
            assertEquals(List.of(plain), MetadataCriteria.spellings(plain), plain);
        }
    }

    @Test
    void typedAndPatternCriteria_areNeverExpanded() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("b", Boolean.TRUE);
        input.put("n", 42);
        input.put("p", "tru%");
        MetadataCriteria criteria = MetadataCriteria.of(input);
        assertEquals(Map.of("b", true, "n", 42), criteria.exact());
        assertTrue(criteria.alternatives().isEmpty());
        assertEquals(List.of(new MetadataCriteria.Pattern("p", "tru%", false)), criteria.patterns());
    }

    @Test
    void containments_holdEachSpellingAsValueAndAsListMember() {
        MetadataCriteria.Alternative alternative = MetadataCriteria.of(Map.of("_readOnly", "true")).alternatives().getFirst();
        assertEquals(List.of(
                Map.of("_readOnly", "true"),
                Map.of("_readOnly", List.of("true")),
                Map.of("_readOnly", true),
                Map.of("_readOnly", List.of(true))), alternative.containments());

        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("k", null);
        List<Map<String, Object>> nulls = MetadataCriteria.of(Map.of("k", "null")).alternatives().getFirst().containments();
        assertEquals(nullValue, nulls.get(2));
        assertEquals(Arrays.asList((Object) null), nulls.get(3).get("k"));
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
