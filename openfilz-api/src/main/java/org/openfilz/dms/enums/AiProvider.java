package org.openfilz.dms.enums;

/**
 * Chat-LLM providers a user can select in their personal AI settings (BYOK).
 * Only the chat model is user-selectable — embeddings always use the
 * server-configured model (the pgvector schema is pinned to its 768-dim output).
 */
public enum AiProvider {
    /** OpenAI platform (api.openai.com). */
    OPENAI,
    /** Anthropic Claude (api.anthropic.com). */
    ANTHROPIC,
    /** Google Gemini via the GenAI / Gemini Developer API (API-key auth). */
    GOOGLE,
    /** Any OpenAI-compatible endpoint (OpenRouter, Mistral, vLLM, LM Studio…) — requires a base URL. */
    OPENAI_COMPATIBLE
}
