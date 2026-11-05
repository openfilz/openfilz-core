package org.openfilz.dms.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The text last extracted from a file, so that reading a long document in pages does not parse the
 * file again for each page when full-text search is off. Keyed on the document and its
 * {@code updated_at}, so a new content is never served from here.
 * <p>
 * This holds text, not a decision: the caller checks access on every call before asking, whether
 * or not the text is here. Bounded in total characters, since one workbook can extract to millions.
 */
@Component
public class ExtractedTextCache {

    private static final long MAX_TOTAL_CHARS = 32L * 1024 * 1024;
    private static final Duration EXPIRY = Duration.ofMinutes(5);

    private record Key(UUID documentId, OffsetDateTime updatedAt) {
    }

    private final Cache<Key, String> texts = Caffeine.newBuilder()
            .maximumWeight(MAX_TOTAL_CHARS)
            .weigher((Key key, String text) -> text.length())
            .expireAfterAccess(EXPIRY)
            .build();

    /** The cached text, or null. */
    public String get(UUID documentId, OffsetDateTime updatedAt) {
        return texts.getIfPresent(new Key(documentId, updatedAt));
    }

    public void put(UUID documentId, OffsetDateTime updatedAt, String text) {
        texts.put(new Key(documentId, updatedAt), text);
    }
}
