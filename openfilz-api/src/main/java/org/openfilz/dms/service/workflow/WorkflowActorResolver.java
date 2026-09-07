package org.openfilz.dms.service.workflow;

import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Builds the {@link Authentication} under which the engine acts when there is no HTTP caller
 * (auto-start after an upload runs under the uploader, but the sweeper and the completion of
 * queued actions do not). Core: a synthetic JWT carrying the trusted e-mail, like e-Sign.
 */
public interface WorkflowActorResolver {

    String AZP_WORKFLOW_SERVICE = "openfilz-workflow-service";

    Mono<Authentication> authenticationFor(String email);
}
