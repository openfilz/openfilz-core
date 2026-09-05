package org.openfilz.dms.service.insight;

import org.apache.tika.metadata.Metadata;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The tier-1 document insight: the file's own metadata as Tika reports it, mapped from the
 * standard keys (Dublin Core first, legacy names as fallbacks). Every field is optional; most
 * office documents and PDFs carry at least a page count or a creation date.
 */
public record TikaFileMetadata(String title, String author, OffsetDateTime createdAt, OffsetDateTime modifiedAt,
                               Integer pageCount, String language) {

    public static final TikaFileMetadata EMPTY = new TikaFileMetadata(null, null, null, null, null, null);

    public static TikaFileMetadata from(Metadata metadata) {
        if (metadata == null) {
            return EMPTY;
        }
        return new TikaFileMetadata(
                text(metadata, 512, "dc:title", "title"),
                text(metadata, 255, "dc:creator", "meta:author", "Author", "creator"),
                date(metadata, "dcterms:created", "meta:creation-date", "Creation-Date", "created"),
                date(metadata, "dcterms:modified", "Last-Modified", "modified", "meta:save-date"),
                integer(metadata, "xmpTPg:NPages", "meta:page-count", "Page-Count", "Slide-Count"),
                language(metadata, "language", "dc:language"));
    }

    public boolean isEmpty() {
        return title == null && author == null && createdAt == null && modifiedAt == null
                && pageCount == null && language == null;
    }

    private static String text(Metadata metadata, int maxLength, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                String trimmed = value.trim();
                return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
            }
        }
        return null;
    }

    private static Integer integer(Metadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                try {
                    int parsed = Integer.parseInt(value.trim());
                    return parsed > 0 ? parsed : null;
                } catch (NumberFormatException ignored) {
                    // next key
                }
            }
        }
        return null;
    }

    private static OffsetDateTime date(Metadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            OffsetDateTime parsed = parseDate(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** Tika emits ISO-8601, with or without a zone; anything else is ignored. */
    static OffsetDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // try the other shapes
        }
        try {
            return Instant.parse(trimmed).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // try the other shapes
        }
        try {
            return LocalDateTime.parse(trimmed).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** The BCP-47 primary tag, lower-cased ("fr-FR" -> "fr"); null when absent or not a tag. */
    private static String language(Metadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                String primary = value.trim().split("[-_]")[0].toLowerCase(Locale.ROOT);
                if (primary.length() >= 2 && primary.length() <= 8 && primary.chars().allMatch(Character::isLetter)) {
                    return primary;
                }
            }
        }
        return null;
    }
}
