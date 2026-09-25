package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how documents are chunked before embedding. Spring AI's {@link TokenTextSplitter} (2.0.x) has
 * no overlap: the value long configured as {@code chunk-overlap} was always its minimum chunk size in
 * characters, now {@code min-chunk-size-chars} with {@code chunk-overlap} kept as a deprecated alias.
 */
class EmbeddingSplitterConfigTest {

    @Test
    @DisplayName("the splitter gets chunk size in tokens, the minimum chunk size in chars, and the fixed limits")
    void splitterConfiguration() throws Exception {
        AiProperties.EmbeddingConfig config = new AiProperties.EmbeddingConfig();
        TokenTextSplitter splitter = DocumentEmbeddingServiceImpl.newSplitter(config);

        assertThat(field(splitter, "chunkSize")).isEqualTo(1000);
        assertThat(field(splitter, "minChunkSizeChars")).isEqualTo(AiProperties.EmbeddingConfig.DEFAULT_MIN_CHUNK_SIZE_CHARS);
        assertThat(field(splitter, "minChunkLengthToEmbed")).isEqualTo(DocumentEmbeddingServiceImpl.MIN_CHUNK_LENGTH_TO_EMBED);
        assertThat(field(splitter, "maxNumChunks")).isEqualTo(DocumentEmbeddingServiceImpl.MAX_NUM_CHUNKS);
        assertThat(field(splitter, "keepSeparator")).isEqualTo(true);
        assertThat(field(splitter, "punctuationMarks")).isEqualTo(List.of('.', '!', '?', '\n'));
    }

    @Test
    @DisplayName("min-chunk-size-chars wins, the deprecated chunk-overlap is its fallback, 200 otherwise")
    void minChunkSizePrecedence() throws Exception {
        AiProperties.EmbeddingConfig config = new AiProperties.EmbeddingConfig();
        assertThat(config.resolveMinChunkSizeChars()).isEqualTo(200);

        config.setChunkOverlap(120);   // an old deployment's value keeps its effect
        assertThat(config.resolveMinChunkSizeChars()).isEqualTo(120);
        assertThat(field(DocumentEmbeddingServiceImpl.newSplitter(config), "minChunkSizeChars")).isEqualTo(120);

        config.setMinChunkSizeChars(350);
        assertThat(config.resolveMinChunkSizeChars()).isEqualTo(350);
        assertThat(field(DocumentEmbeddingServiceImpl.newSplitter(config), "minChunkSizeChars")).isEqualTo(350);
    }

    @Test
    @DisplayName("consecutive chunks share no text: the splitter does not overlap")
    void chunksDoNotOverlap() {
        AiProperties.EmbeddingConfig config = new AiProperties.EmbeddingConfig();
        config.setChunkSize(40);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            text.append("Sentence number ").append(i).append(" is about invoices and contracts. ");
        }
        List<Document> chunks = DocumentEmbeddingServiceImpl.newSplitter(config).apply(List.of(new Document(text.toString())));

        assertThat(chunks).hasSizeGreaterThan(2);
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).getText();
            String tail = previous.substring(Math.max(0, previous.length() - 30)).strip();
            assertThat(chunks.get(i).getText()).as("chunk %d must not repeat the end of chunk %d", i, i - 1)
                    .doesNotStartWith(tail);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = TokenTextSplitter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
