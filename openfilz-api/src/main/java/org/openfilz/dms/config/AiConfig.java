package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Spring AI configuration.
 * Only active when openfilz.ai.active=true.
 * <p>
 * PgVectorStore auto-configuration is excluded globally (application.yml) because
 * the project uses R2DBC (no auto-created JdbcTemplate). When AI is active,
 * this config creates the JDBC DataSource, JdbcTemplate, and PgVectorStore manually.
 * <p>
 * Since both Ollama and OpenAI starters are on the classpath, Spring AI may create
 * multiple ChatModel/EmbeddingModel beans. This config selects the correct one
 * based on which provider has chat/embedding enabled.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiConfig {

    @Bean
    @Primary
    ChatModel primaryChatModel(Map<String, ChatModel> chatModels,
                               @Value("${spring.ai.ollama.chat.enabled:false}") boolean ollamaEnabled,
                               @Value("${spring.ai.openai.chat.enabled:false}") boolean openaiEnabled) {
        if (ollamaEnabled && chatModels.containsKey("ollamaChatModel")) {
            log.info("AI chat provider: Ollama");
            return chatModels.get("ollamaChatModel");
        }
        if (openaiEnabled && chatModels.containsKey("openAiChatModel")) {
            log.info("AI chat provider: OpenAI");
            return chatModels.get("openAiChatModel");
        }
        // Fallback: pick the first available
        log.warn("No explicit chat provider enabled, using first available: {}", chatModels.keySet());
        return chatModels.values().iterator().next();
    }

    @Bean
    @Primary
    EmbeddingModel primaryEmbeddingModel(Map<String, EmbeddingModel> embeddingModels,
                                         @Value("${spring.ai.ollama.embedding.enabled:false}") boolean ollamaEnabled,
                                         @Value("${spring.ai.openai.embedding.enabled:false}") boolean openaiEnabled) {
        if (ollamaEnabled && embeddingModels.containsKey("ollamaEmbeddingModel")) {
            log.info("AI embedding provider: Ollama");
            return embeddingModels.get("ollamaEmbeddingModel");
        }
        if (openaiEnabled && embeddingModels.containsKey("openAiEmbeddingModel")) {
            log.info("AI embedding provider: OpenAI");
            return embeddingModels.get("openAiEmbeddingModel");
        }
        log.warn("No explicit embedding provider enabled, using first available: {}", embeddingModels.keySet());
        return embeddingModels.values().iterator().next();
    }

    @Bean
    ChatClient chatClient(@Qualifier("primaryChatModel") ChatModel chatModel,
                           AiProperties aiProperties, DocumentAiTools documentAiTools) {
        return ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.getSystemPrompt())
                .defaultToolCallbacks(MethodToolCallbackProvider.builder()
                        .toolObjects(documentAiTools)
                        .build())
                .build();
    }

    @Bean
    DataSource aiDataSource(
            @Value("${spring.flyway.url}") String jdbcUrl,
            @Value("${spring.flyway.user}") String username,
            @Value("${spring.flyway.password}") String password) {
        return DataSourceBuilder.create()
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    JdbcTemplate aiJdbcTemplate(DataSource aiDataSource) {
        return new JdbcTemplate(aiDataSource);
    }

    @Bean
    VectorStore vectorStore(JdbcTemplate aiJdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(aiJdbcTemplate, embeddingModel)
                .dimensions(768)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false) // Flyway manages the schema
                .build();
    }
}
