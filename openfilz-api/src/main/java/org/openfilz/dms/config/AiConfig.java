package org.openfilz.dms.config;

import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Spring AI configuration.
 * Only active when openfilz.ai.active=true.
 */
@Lazy
@Configuration
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatModel chatModel, AiProperties aiProperties, DocumentAiTools documentAiTools) {
        return ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.getSystemPrompt())
                .defaultToolCallbacks(MethodToolCallbackProvider.builder()
                        .toolObjects(documentAiTools)
                        .build())
                .build();
    }
}
