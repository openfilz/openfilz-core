package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.response.UploadResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Hot folders: starts the active definitions whose trigger folders contain the parent of a
 * freshly uploaded file, as the uploader. Called by the upload endpoints after smart filing
 * (so the trigger sees the final folder); never fails an upload.
 */
public interface WorkflowTriggerService {

    Mono<List<UploadResponse>> afterUpload(List<UploadResponse> responses);
}
