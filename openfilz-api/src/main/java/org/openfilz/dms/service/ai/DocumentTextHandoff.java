package org.openfilz.dms.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Carries the text of a freshly uploaded file from the pass that extracted it (the standalone
 * embedding) to the smart filing that follows, so one upload is parsed by Tika once instead of
 * twice — the second parse re-read the file from storage and re-ran Tika for the same characters
 * the first pass had just produced.
 *
 * <p>It is a hand-off buffer, not a cache of documents, and three independent bounds keep it that
 * way — in that order of importance:
 * <ol>
 *   <li><b>It is only written when nothing else keeps the text.</b> The sole producer is the
 *       standalone embedding path, which runs exactly when full-text indexing is off; with
 *       OpenSearch on, the filing reads the indexed content and this class stays empty. The
 *       optimisation can therefore never become an overhead on top of the index.</li>
 *   <li><b>{@link #take} removes what it returns.</b> The normal life of an entry is the seconds
 *       between the upload's Tika pass and the filing that consumes it.</li>
 *   <li><b>What is left expires and is capped.</b> Entries nobody took (filing off for that user,
 *       an upload never filed, a failed job) go after {@code ttl}, and before that Caffeine evicts
 *       by total size: an entry weighs its own character count and the sum never exceeds
 *       {@code max-characters}. That is the worst case, whatever the number of uploads in flight.</li>
 * </ol>
 *
 * <p>Both switches are read per call, never captured: {@code openfilz.ai.auto-file.active} is a
 * runtime toggle (native images resolve bean conditions at build time).
 */
@Slf4j
@Service
public class DocumentTextHandoff {

    private final AiProperties aiProperties;
    private final Cache<UUID, String> entries;

    public DocumentTextHandoff(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        AiProperties.AutoFile.TextHandoff config = aiProperties.getAutoFile().getTextHandoff();
        Duration ttl = config.getTtl() == null || config.getTtl().isNegative() || config.getTtl().isZero()
                ? Duration.ofMinutes(10) : config.getTtl();
        this.entries = Caffeine.newBuilder()
                .maximumWeight(Math.max(1, config.getMaxCharacters()))
                .weigher((UUID id, String text) -> text.length())
                .expireAfterWrite(ttl)
                .build();
    }

    /**
     * Hand over the text a Tika pass just produced. No-op unless a filing may want it, so a
     * deployment with smart filing off never holds a character.
     *
     * @param head the text, truncated by the caller to what the filing can use
     */
    public void offer(UUID documentId, String head) {
        if (documentId == null || head == null || head.isBlank() || !enabled()) {
            return;
        }
        entries.put(documentId, head);
    }

    /** The text handed over for this document, if any — and it is gone once returned. */
    public Optional<String> take(UUID documentId) {
        if (documentId == null || !enabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.asMap().remove(documentId));
    }

    /** The document is gone (delete, replaced content): drop whatever was held for it. */
    public void forget(UUID documentId) {
        if (documentId != null) {
            entries.invalidate(documentId);
        }
    }

    /** Held entries; for tests, to prove nothing accumulates. */
    public long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }

    private boolean enabled() {
        return aiProperties.isActive()
                && aiProperties.getAutoFile().isActive()
                && aiProperties.getAutoFile().getTextHandoff().isEnabled();
    }
}
