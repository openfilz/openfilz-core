package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.response.EmbeddingBackfillStatus;
import org.openfilz.dms.service.impl.EmbeddingBackfillService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * The embedding backfill for the in-app assistant and MCP agents: the same job as
 * {@code POST /api/v1/ai/embeddings/backfill} — embed the documents that have no vector (or all
 * of them), optionally under one folder — started and followed from a conversation. Built per
 * request by {@code EmbeddingAiToolsContributor}; the role gate is enforced here.
 */
@Slf4j
public class EmbeddingAiTools {

    private final EmbeddingBackfillService backfillService;
    private final ReorganizationPlanService planService;
    private final AiProperties aiProperties;
    private final AiToolRolePolicy rolePolicy;
    private String userEmail;
    private Authentication authentication;

    public EmbeddingAiTools(EmbeddingBackfillService backfillService, ReorganizationPlanService planService,
                            AiProperties aiProperties, AiToolRolePolicy rolePolicy) {
        this.backfillService = backfillService;
        this.planService = planService;
        this.aiProperties = aiProperties;
        this.rolePolicy = rolePolicy;
    }

    public EmbeddingAiTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    @Tool(description = "Start an embedding backfill: (re)compute the vectors of existing documents so that similarity "
            + "search, smart filing and the learned classifier see them. Without force only the documents that have no "
            + "vector are embedded (the repair after a failed upload embedding, or the re-embedding after the vector "
            + "store was wiped for an embedding-provider switch); with force every document in scope is embedded again. "
            + "Runs in the background: returns a job id to follow with getEmbeddingBackfillStatus. No document is "
            + "moved or changed.")
    public String backfillEmbeddings(
            @ToolParam(required = false, description = "Name (or id) of the folder whose subtree to embed; null or 'root' for the whole library") String folder,
            @ToolParam(required = false, description = "Embed every document in scope again, not only those without a vector (default false)") Boolean force) {
        if (rolePolicy != null && !rolePolicy.isAllowed(authentication, ToolCapability.DOCUMENT_WRITE)) {
            return "Not permitted: your OpenFilz role does not allow re-embedding documents (DOCUMENT_WRITE).";
        }
        if (backfillService == null || aiProperties == null || !aiProperties.isActive()) {
            return "The AI feature is not active on this deployment.";
        }
        UUID folderId;
        try {
            folderId = planService == null ? parseId(folder) : planService.rootIdOf(folder, caller());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        boolean forced = Boolean.TRUE.equals(force);
        try {
            EmbeddingBackfillStatus started = backfillService.backfill(folderId, forced, userEmail).block();
            if (started == null) {
                return "The backfill could not be started.";
            }
            return "Embedding backfill started (job " + started.jobId() + ", scope: "
                    + (folderId == null ? "the whole library" : "folder " + (folder == null ? folderId : folder))
                    + ", " + (forced ? "every document" : "documents without a vector") + "). "
                    + "It runs in the background: call getEmbeddingBackfillStatus with this job id to follow it.";
        } catch (Exception e) {
            log.warn("[AI-TOOL] backfillEmbeddings failed: {}", e.toString());
            return "The backfill could not be started: " + e.getMessage();
        }
    }

    @Tool(description = "Progress of an embedding backfill started with backfillEmbeddings: how many documents were "
            + "queued, embedded, skipped (no text) or failed, and whether the job is done.")
    public String getEmbeddingBackfillStatus(
            @ToolParam(description = "Id of the job returned by backfillEmbeddings") String jobId) {
        if (rolePolicy != null && !rolePolicy.isAllowed(authentication, ToolCapability.DOCUMENT_READ)) {
            return "Not permitted: your OpenFilz role does not allow reading documents (DOCUMENT_READ).";
        }
        if (backfillService == null || aiProperties == null || !aiProperties.isActive()) {
            return "The AI feature is not active on this deployment.";
        }
        UUID id;
        try {
            id = parseId(jobId);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (id == null) {
            return "Give the id of the backfill job.";
        }
        Optional<EmbeddingBackfillStatus> status = backfillService.backfillStatus(id);
        if (status.isEmpty()) {
            return "No backfill job '" + jobId + "' is known (jobs are kept in memory until the API restarts).";
        }
        EmbeddingBackfillStatus s = status.get();
        return "Backfill " + s.jobId() + " is " + s.status() + ": " + s.total() + " document(s) queued, "
                + s.done() + " embedded, " + s.skipped() + " skipped (no text), " + s.failed() + " failed"
                + (s.finishedAt() == null ? "." : "; finished at " + s.finishedAt() + ".");
    }

    private static UUID parseId(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim()) || "root".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + value + "' is not an id.");
        }
    }

    private ReorganizationPlanService.Caller caller() {
        return new ReorganizationPlanService.Caller(userEmail, authentication);
    }
}
