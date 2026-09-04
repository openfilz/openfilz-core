package org.openfilz.dms.service.filing;

import org.openfilz.dms.dto.response.AutoFileJobView;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Smart filing: OpenFilz chooses the destination folder of a document on the user's explicit
 * request. A filing is a one-item reorganisation plan produced after ingestion (neighbour vote,
 * then the model) and applied at once; the upload itself is never delayed.
 * <p>
 * Runtime-switchable ({@code openfilz.ai.auto-file.active}, native-safe): {@code AutoFileConfig}
 * selects the real implementation or the no-op one at startup.
 */
public interface AutoFileService {

    /** True when smart filing is on for this deployment. */
    boolean isActive();

    /**
     * Called after an upload batch: decides from {@code autoFileParam} (the request's explicit
     * choice) or the user's preference whether to file, schedules one job for the successful
     * uploads, and returns the responses with the job ticket attached. Reads the caller from the
     * reactive security context; returns the responses unchanged when nothing is scheduled.
     */
    Mono<List<UploadResponse>> afterUpload(List<UploadResponse> responses, Boolean autoFileParam);

    /** File existing documents on demand for the caller (their Inbox, a selection…). */
    AutoFileJobView schedule(List<UUID> documentIds, Caller caller, Boolean allowNewFolders);

    /** Run the pipeline now for one document, on the caller's thread (the AI tools; bounded by the wait budget). */
    FilingOutcome fileNow(UUID documentId, Caller caller, Boolean allowNewFolders);

    /** A job of the caller's (someone else's answers empty, its existence is not revealed). */
    Optional<AutoFileJobView> job(UUID jobId, Caller caller);

    /** Move every filed document of the job back where it came from. */
    AutoFileJobView undo(UUID jobId, Caller caller);

    /** The latest filing record of a document (the "Filed by OpenFilz" chip), if any. */
    Mono<FilingOutcome> lastFiling(UUID documentId, Caller caller);

    /** Undo one filing by its plan id (the chip's "Move back"). */
    Mono<FilingOutcome> undoFiling(UUID planId, Caller caller);
}
