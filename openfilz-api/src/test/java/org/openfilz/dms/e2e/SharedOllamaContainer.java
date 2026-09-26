package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.List;

/**
 * Singleton Ollama container serving a real LLM to the AI end-to-end tests.
 * <p>
 * The models are deliberately small. {@code qwen2.5:1.5b} (~1 GB) is the lightest model measured to
 * drive this codebase's {@code @Tool} methods reliably: asked to list a folder it emitted a correct
 * tool call 6/6 times, where {@code qwen2.5:0.5b} managed 1/5. {@code nomic-embed-text} (~270 MB)
 * produces the 768-dimension vectors {@code AiConfig} configures {@code PgVectorStore} with.
 * <p>
 * Pulling ~1.3 GB on every run would be untenable, so the first run pulls the models and commits
 * the result to a local image ({@link #CACHED_IMAGE}); later runs start from that image and skip
 * the download entirely. Delete the image to force a refresh.
 */
@Slf4j
public final class SharedOllamaContainer {

    public static final String CHAT_MODEL = "qwen2.5:1.5b";
    public static final String EMBEDDING_MODEL = "nomic-embed-text";

    private static final String BASE_IMAGE = "ollama/ollama:0.13.0";
    private static final String CACHED_IMAGE = "openfilz/tc-ollama-qwen2.5-1.5b:1";

    private static final OllamaContainer OLLAMA_CONTAINER;

    static {
        OLLAMA_CONTAINER = new OllamaContainer(resolveImage())
                // OllamaContainer asks Docker for every available GPU. On a host without an NVIDIA
                // runtime — a CI runner, or Docker Desktop on WSL2 — that request makes the
                // container fail to start outright ("nvidia-container-cli: initialization error").
                // Clearing the device requests runs the model on CPU, which is slower but portable;
                // the models are small enough for that to be the right trade.
                .withCreateContainerCmdModifier(cmd -> {
                    if (cmd.getHostConfig() != null) {
                        cmd.getHostConfig().withDeviceRequests(List.of());
                    }
                })
                .withReuse(true);
        OLLAMA_CONTAINER.start();
        pullModelsIfMissing();
    }

    private SharedOllamaContainer() {
        // Prevent instantiation
    }

    /**
     * Prefer the cached image if a previous run built it; otherwise start from the stock Ollama
     * image, which the static initializer then populates and commits.
     */
    private static DockerImageName resolveImage() {
        if (imageExistsLocally(CACHED_IMAGE)) {
            log.info("[AI-E2E] Using cached Ollama image {}", CACHED_IMAGE);
            return DockerImageName.parse(CACHED_IMAGE).asCompatibleSubstituteFor(BASE_IMAGE);
        }
        log.info("[AI-E2E] No cached Ollama image, starting from {} (models will be pulled once)", BASE_IMAGE);
        return DockerImageName.parse(BASE_IMAGE);
    }

    private static boolean imageExistsLocally(String image) {
        try {
            Process process = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void pullModelsIfMissing() {
        boolean pulledAnything = false;
        for (String model : new String[]{CHAT_MODEL, EMBEDDING_MODEL}) {
            if (!hasModel(model)) {
                log.info("[AI-E2E] Pulling {} (one-off; the result is cached as {})", model, CACHED_IMAGE);
                pull(model);
                pulledAnything = true;
            }
        }
        if (pulledAnything) {
            log.info("[AI-E2E] Committing populated container to {}", CACHED_IMAGE);
            OLLAMA_CONTAINER.commitToImage(CACHED_IMAGE);
        }
    }

    /**
     * The Ollama registry answers transient 5xx now and then (run 36203909630 died on a 503 after
     * the 986 MB blob had downloaded). {@code ollama pull} resumes from the blobs already on disk,
     * so a retry is cheap — only a pull that keeps failing is a real problem.
     */
    private static void pull(String model) {
        long[] backoffSeconds = {10, 30, 60};
        for (int attempt = 0; ; attempt++) {
            try {
                exec("ollama", "pull", model);
                return;
            } catch (IllegalStateException e) {
                if (attempt >= backoffSeconds.length) {
                    throw e;
                }
                log.warn("[AI-E2E] Pulling {} failed (attempt {}), retrying in {}s: {}",
                        model, attempt + 1, backoffSeconds[attempt], e.getMessage());
                try {
                    Thread.sleep(backoffSeconds[attempt] * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean hasModel(String model) {
        // `ollama list` prints the tag, so a substring match on the model name is enough. The
        // bare name matches the ":latest" tag Ollama prints for un-suffixed models.
        return exec("ollama", "list").contains(model.replace(":latest", ""));
    }

    private static String exec(String... command) {
        try {
            var result = OLLAMA_CONTAINER.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Ollama command %s failed (%d): %s"
                        .formatted(String.join(" ", command), result.getExitCode(), tail(result.getStderr())));
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new IllegalStateException("Ollama command failed: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running: " + String.join(" ", command), e);
        }
    }

    /** {@code ollama pull} writes its progress bars to stderr; the error is on the last line. */
    private static String tail(String stderr) {
        String trimmed = stderr == null ? "" : stderr.strip();
        return trimmed.length() <= 300 ? trimmed : "…" + trimmed.substring(trimmed.length() - 300);
    }

    public static OllamaContainer getInstance() {
        return OLLAMA_CONTAINER;
    }

    public static String getBaseUrl() {
        return OLLAMA_CONTAINER.getEndpoint();
    }
}
