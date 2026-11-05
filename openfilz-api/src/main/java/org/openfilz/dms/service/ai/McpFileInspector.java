package org.openfilz.dms.service.ai;

import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * Looks at a file an agent sent through {@link McpFileTransferTools}, decoded, before it is written.
 * The default accepts everything ({@link AcceptAllMcpFileInspector}); an extension layer that wants
 * a say overrides it with a {@code @Primary} bean.
 * <p>
 * Such a file arrives as base64 inside a JSON body, so a web filter reading the upload formats of
 * the REST API never sees it. This is the one place it exists as a file before the write.
 */
public interface McpFileInspector {

    /**
     * @param file the decoded content, only valid until the returned {@code Mono} completes
     * @param fileName the name the agent gave, or the name of the document being replaced
     * @return empty to let the write go on, or why it is refused, as a clause the agent can read
     *         ("it is too old"). An inspector that cannot decide refuses rather than erring: an
     *         error is reported to the agent as is.
     */
    Mono<String> refusal(Path file, String fileName);
}
