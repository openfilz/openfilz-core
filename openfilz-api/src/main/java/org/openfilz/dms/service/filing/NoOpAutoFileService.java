package org.openfilz.dms.service.filing;

import org.openfilz.dms.dto.response.AutoFileJobView;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Selected when smart filing (or the AI feature) is off: uploads are never touched. */
@Service
@Lazy
@Qualifier("noOpAutoFileService")
public class NoOpAutoFileService implements AutoFileService {

    private static final String OFF = "Smart filing is not active on this deployment";

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public Mono<List<UploadResponse>> afterUpload(List<UploadResponse> responses, Boolean autoFileParam) {
        return Mono.just(responses);
    }

    @Override
    public AutoFileJobView schedule(List<UUID> documentIds, Caller caller, Boolean allowNewFolders) {
        throw new IllegalStateException(OFF);
    }

    @Override
    public FilingOutcome fileNow(UUID documentId, Caller caller, Boolean allowNewFolders) {
        throw new IllegalStateException(OFF);
    }

    @Override
    public Optional<AutoFileJobView> job(UUID jobId, Caller caller) {
        return Optional.empty();
    }

    @Override
    public AutoFileJobView undo(UUID jobId, Caller caller) {
        throw new IllegalStateException(OFF);
    }

    @Override
    public Mono<FilingOutcome> lastFiling(UUID documentId, Caller caller) {
        return Mono.empty();
    }

    @Override
    public Mono<FilingOutcome> undoFiling(UUID planId, Caller caller) {
        return Mono.error(new IllegalStateException(OFF));
    }
}
