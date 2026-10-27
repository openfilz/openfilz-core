package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        sqlUtils.appendMetadataCriteria("d.", "metadata", Map.of("Client", "ACME", "project", "beta%"), sql);
        assertThat(sql.toString())
                .contains("(lower(d.metadata::text)::jsonb) @> lower(:criteria)::jsonb ")
                .contains("(lower(d.metadata::text)::jsonb) ?& :mkeys ")
                .contains("(lower(d.metadata::text)::jsonb) ->> :mk_0 ILIKE :mv_0 ESCAPE '\\' ");
    }
}
