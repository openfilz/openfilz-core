package org.openfilz.dms.service.insight;

import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping of Tika's metadata keys to the tier-1 insight, including the legacy fallbacks. */
class TikaFileMetadataTest {

    @Test
    @DisplayName("Dublin Core keys win, legacy keys fill the gaps")
    void mapsStandardKeysWithFallbacks() {
        Metadata metadata = new Metadata();
        metadata.set("dc:title", "  Quarterly report  ");
        metadata.set("Author", "Alice");
        metadata.set("dcterms:created", "2026-03-01T10:15:30Z");
        metadata.set("Last-Modified", "2026-03-02T08:00:00");
        metadata.set("xmpTPg:NPages", "12");
        metadata.set("language", "fr-FR");

        TikaFileMetadata insight = TikaFileMetadata.from(metadata);

        assertThat(insight.title()).isEqualTo("Quarterly report");
        assertThat(insight.author()).isEqualTo("Alice");
        assertThat(insight.createdAt()).isEqualTo(OffsetDateTime.of(2026, 3, 1, 10, 15, 30, 0, ZoneOffset.UTC));
        assertThat(insight.modifiedAt()).isEqualTo(OffsetDateTime.of(2026, 3, 2, 8, 0, 0, 0, ZoneOffset.UTC));
        assertThat(insight.pageCount()).isEqualTo(12);
        assertThat(insight.language()).isEqualTo("fr");
        assertThat(insight.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("garbage values are ignored rather than failing the extraction")
    void ignoresUnparseableValues() {
        Metadata metadata = new Metadata();
        metadata.set("xmpTPg:NPages", "many");
        metadata.set("dcterms:created", "yesterday");
        metadata.set("language", "42");

        TikaFileMetadata insight = TikaFileMetadata.from(metadata);

        assertThat(insight.pageCount()).isNull();
        assertThat(insight.createdAt()).isNull();
        assertThat(insight.language()).isNull();
        assertThat(insight.isEmpty()).isTrue();
        assertThat(TikaFileMetadata.from(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("over-long titles are truncated to the column width")
    void truncatesLongTitles() {
        Metadata metadata = new Metadata();
        metadata.set("title", "x".repeat(600));
        assertThat(TikaFileMetadata.from(metadata).title()).hasSize(512);
    }
}
