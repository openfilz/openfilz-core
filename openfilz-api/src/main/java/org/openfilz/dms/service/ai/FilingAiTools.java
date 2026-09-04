package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import org.openfilz.dms.service.filing.AutoFileService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Smart filing on demand for the in-app assistant and MCP agents: file existing documents
 * (an Inbox, a selection) through the same pipeline as an upload with autoFile — inline, since
 * tools are synchronous. Built per request by {@code FilingAiToolsContributor}.
 */
@Slf4j
public class FilingAiTools implements AiToolTurnEffects {

    private static final int MAX_PER_CALL = 10;

    private final AutoFileService autoFileService;
    private final AiToolRolePolicy rolePolicy;
    private String userEmail;
    private Authentication authentication;

    private final Set<String> modifiedFolders = ConcurrentHashMap.newKeySet();
    private final List<String> performedActions = new CopyOnWriteArrayList<>();

    public FilingAiTools(AutoFileService autoFileService, AiToolRolePolicy rolePolicy) {
        this.autoFileService = autoFileService;
        this.rolePolicy = rolePolicy;
    }

    public FilingAiTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    @Override
    public Set<String> modifiedFolders() {
        return modifiedFolders;
    }

    @Override
    public List<String> performedActions() {
        return performedActions;
    }

    @Tool(description = "Let OpenFilz choose the right folder for existing documents (smart filing): each document "
            + "is moved to the folder where its closest documents live, or to the folder the model picks; it stays "
            + "where it is when nothing is confident enough. Use it when the user asks to file, sort or put away "
            + "documents (e.g. an Inbox). Up to 10 documents per call; reports what moved where and why.")
    public String fileDocuments(
            @ToolParam(description = "Comma-separated ids of the documents to file") String documentIds,
            @ToolParam(required = false, description = "Whether filing may create new folders (default: the user's preference)") Boolean allowNewFolders) {
        if (rolePolicy != null && !rolePolicy.isAllowed(authentication, ToolCapability.DOCUMENT_WRITE)) {
            return "Not permitted: your OpenFilz role does not allow moving documents (DOCUMENT_WRITE).";
        }
        if (autoFileService == null || !autoFileService.isActive()) {
            return "Smart filing is not active on this deployment.";
        }
        List<UUID> ids = new ArrayList<>();
        for (String token : (documentIds == null ? "" : documentIds).split("[,;\\s]+")) {
            if (token.isBlank()) continue;
            try {
                ids.add(UUID.fromString(token.trim()));
            } catch (IllegalArgumentException e) {
                return "documentIds must be document ids, got '" + token + "'.";
            }
        }
        if (ids.isEmpty()) {
            return "Give the ids of the documents to file (use queryDocuments to find them).";
        }
        if (ids.size() > MAX_PER_CALL) {
            return "At most " + MAX_PER_CALL + " documents per call; split the list.";
        }
        StringBuilder sb = new StringBuilder();
        int filed = 0;
        for (UUID id : ids) {
            try {
                FilingOutcome outcome = autoFileService.fileNow(id, new Caller(userEmail, authentication), allowNewFolders);
                sb.append("- ").append(outcome.name() != null ? outcome.name() : id).append(": ");
                if (FilingOutcome.FILED.equals(outcome.status())) {
                    filed++;
                    sb.append("moved to ").append(outcome.toPath()).append(" (").append(outcome.reason()).append(')');
                    modifiedFolders.add(AiToolTurnEffects.folderKey(outcome.fromFolderId()));
                    modifiedFolders.add(AiToolTurnEffects.folderKey(outcome.toFolderId()));
                } else {
                    sb.append(outcome.status().toLowerCase()).append(" — ").append(outcome.reason());
                }
                sb.append('\n');
            } catch (Exception e) {
                log.warn("[AI-TOOL] fileDocuments failed for {}: {}", id, e.toString());
                sb.append("- ").append(id).append(": failed — ").append(e.getMessage()).append('\n');
            }
        }
        if (filed > 0) {
            performedActions.add("Filed " + filed + " document(s) with smart filing");
        }
        return filed + " of " + ids.size() + " document(s) filed.\n" + sb;
    }
}
