package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What OpenFilz derived from a document's content, apart from the user-owned metadata JSON:
 * tier 1 is the file's own metadata as Tika found it, tier 2 the AI-derived category, summary,
 * keywords and entities. Read through the repository; written by
 * {@link org.openfilz.dms.service.insight.DocumentInsightStore} (upserts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_document_insights")
public class AiDocumentInsight {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @Column("document_id")
    private UUID documentId;

    @Column("file_title")
    private String fileTitle;

    @Column("file_author")
    private String fileAuthor;

    @Column("file_created_at")
    private OffsetDateTime fileCreatedAt;

    @Column("file_modified_at")
    private OffsetDateTime fileModifiedAt;

    @Column("page_count")
    private Integer pageCount;

    @Column("language")
    private String language;

    @Column("category")
    private String category;

    @Column("summary")
    private String summary;

    @Column("keywords")
    private String[] keywords;

    @Column("entities")
    private Json entities;

    @Column("tier")
    private Integer tier;

    @Column("model")
    private String model;

    @Column("prompt_version")
    private Integer promptVersion;

    @Column("status")
    private String status;

    @Column("error")
    private String error;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;
}
