package org.openfilz.dms.service.ai;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/** Core default {@link McpFileInspector}: every file goes on to the regular upload pipeline. */
@Service
@Lazy
public class AcceptAllMcpFileInspector implements McpFileInspector {

    @Override
    public Mono<String> refusal(Path file, String fileName) {
        return Mono.empty();
    }
}
