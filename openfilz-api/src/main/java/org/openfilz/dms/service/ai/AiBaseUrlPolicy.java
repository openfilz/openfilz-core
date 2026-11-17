package org.openfilz.dms.service.ai;

/**
 * Which base URL a user may give the OpenAI-compatible server of their personal AI settings.
 * <p>
 * Consulted wherever such a URL is about to be used: when the settings are saved, by "test
 * connection", by the model listing, and each time the user's model is built from the stored row
 * (a row saved under an earlier rule is checked too). The server sends requests to that address and
 * hands the answer back to the user, so a deployment that shares its network with other services
 * needs a say in where they may go.
 * <p>
 * The core rule ({@link SchemeOnlyAiBaseUrlPolicy}) only asks for {@code http} or {@code https}; a
 * {@code @Primary} bean replaces it. An implementation may resolve the host: callers run it on a
 * blocking-friendly scheduler, never on an event loop.
 */
public interface AiBaseUrlPolicy {

    /**
     * The URL to use (trimmed). A refused URL is a
     * {@link org.springframework.web.server.ResponseStatusException} with status 400 whose reason
     * says why; the message must not carry the API key.
     */
    String validate(String baseUrl);
}
