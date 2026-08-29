package com.example.openfilzmcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Connects to OpenFilz /mcp via the Spring AI MCP client and prints the advertised tools. In a real
 * app you would hand {@code toolCallbackProvider.getToolCallbacks()} to a {@code ChatClient} so the
 * model can call them — that is the whole point of the MCP path.
 */
@SpringBootApplication
public class OpenFilzMcpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenFilzMcpClientApplication.class, args);
    }

    @Bean
    CommandLineRunner listTools(ToolCallbackProvider mcpTools) {
        return args -> {
            ToolCallback[] callbacks = mcpTools.getToolCallbacks();
            System.out.printf("OpenFilz advertises %d MCP tools:%n", callbacks.length);
            for (ToolCallback cb : callbacks) {
                System.out.println("  - " + cb.getToolDefinition().name());
            }
        };
    }
}
