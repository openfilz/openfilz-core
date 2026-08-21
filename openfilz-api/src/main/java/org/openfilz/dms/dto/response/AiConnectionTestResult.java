package org.openfilz.dms.dto.response;

/**
 * Result of a connection test against a chat-LLM provider (settings "Test" button).
 */
public record AiConnectionTestResult(boolean ok, String message, long latencyMs) {
}
