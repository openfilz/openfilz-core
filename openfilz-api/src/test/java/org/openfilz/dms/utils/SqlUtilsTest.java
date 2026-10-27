package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlUtilsTest {

    private final SqlUtils sqlUtils = new SqlUtils(null);

    @Test
    void escapeLike_makesWildcardsAndTheEscapeCharacterLiteral() {
        assertThat(SqlUtils.escapeLike("abc_123.pdf")).isEqualTo("abc\\_123.pdf");
        assertThat(SqlUtils.escapeLike("100%\\done")).isEqualTo("100\\%\\\\done");
        assertThat(SqlUtils.escapeLike("plain")).isEqualTo("plain");
    }

    @Test
    void likeCriteria_isACaseInsensitiveEscapedMatch() {
        StringBuilder sql = new StringBuilder();
        sqlUtils.appendLikeCriteria("d.", "name", sql);
        assertThat(sql).hasToString("d.name ILIKE :name ESCAPE '\\' ");
    }

    @Test
    void metadataCriteria_readTheLowerCasedColumn() {
        StringBuilder sql = new StringBuilder();
        sqlUtils.appendMetadataCriteria("d.", "metadata", Map.of("Client", "ACME", "year", 2024, "project", "beta%"), sql);
        assertThat(sql.toString())
                .contains("(lower(d.metadata::text)::jsonb) @> lower(:criteria)::jsonb ")
                .contains("(lower(d.metadata::text)::jsonb) @> ANY(ARRAY(SELECT jsonb_array_elements(lower(:malt_0)::jsonb))) ")
                .contains("(lower(d.metadata::text)::jsonb) ?& :mkeys ")
                .contains("(lower(d.metadata::text)::jsonb) ->> :mk_0 ILIKE :mv_0 ESCAPE '\\' ");
    }

    @Test
    void metadataCriteria_stringsOnly_needNoContainmentObject() {
        StringBuilder sql = new StringBuilder();
        sqlUtils.appendMetadataCriteria(null, "metadata", Map.of("b", "true", "a", "x"), sql);
        assertThat(sql).hasToString(
                "(lower(metadata::text)::jsonb) @> ANY(ARRAY(SELECT jsonb_array_elements(lower(:malt_0)::jsonb))) "
                        + "AND (lower(metadata::text)::jsonb) @> ANY(ARRAY(SELECT jsonb_array_elements(lower(:malt_1)::jsonb))) ");
    }

    @Test
    void metadataCriteria_fromFiveStrings_orTheirContainments() {
        Map<String, Object> five = new java.util.LinkedHashMap<>();
        for (int i = 0; i < SqlUtils.METADATA_OR_FORM_FROM; i++) {
            five.put("k" + i, i == 0 ? "true" : "v" + i);
        }
        StringBuilder sql = new StringBuilder();
        sqlUtils.appendMetadataCriteria(null, "metadata", five, sql);
        assertThat(sql.toString())
                .doesNotContain("ANY(")
                // "true": 2 spellings x (scalar, list) = 4 containments
                .startsWith("((lower(metadata::text)::jsonb) @> lower(:malt_0_0)::jsonb "
                        + "OR (lower(metadata::text)::jsonb) @> lower(:malt_0_1)::jsonb "
                        + "OR (lower(metadata::text)::jsonb) @> lower(:malt_0_2)::jsonb "
                        + "OR (lower(metadata::text)::jsonb) @> lower(:malt_0_3)::jsonb ) "
                        + "AND ((lower(metadata::text)::jsonb) @> lower(:malt_1_0)::jsonb "
                        + "OR (lower(metadata::text)::jsonb) @> lower(:malt_1_1)::jsonb ) ")
                .endsWith("AND ((lower(metadata::text)::jsonb) @> lower(:malt_4_0)::jsonb "
                        + "OR (lower(metadata::text)::jsonb) @> lower(:malt_4_1)::jsonb ) ");
    }

    @Test
    void metadataCriteria_bindsTheSameNamesTheSqlUses() {
        Map<String, Object> five = new java.util.LinkedHashMap<>();
        for (int i = 0; i < SqlUtils.METADATA_OR_FORM_FROM; i++) {
            five.put("k" + i, i == 0 ? "true" : "v" + i);
        }
        for (Map<String, Object> criteria : List.of(Map.<String, Object>of("a", "true", "b", 2, "c", "x%"), five)) {
            StringBuilder sql = new StringBuilder();
            sqlUtils.appendMetadataCriteria(null, "metadata", criteria, sql);
            Set<String> inSql = new HashSet<>();
            Matcher m = Pattern.compile("(?<!:):([a-z_0-9]+)").matcher(sql);
            while (m.find()) {
                inSql.add(m.group(1));
            }
            Set<String> bound = new HashSet<>();
            DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
            when(spec.bind(anyString(), any())).thenAnswer(invocation -> {
                bound.add(invocation.getArgument(0));
                return spec;
            });
            new SqlUtils(JsonMapper.builder().build()).bindMetadataCriteria(criteria, spec);
            assertThat(bound).isEqualTo(inSql);
        }
    }
}
