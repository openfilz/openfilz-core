package org.openfilz.dms.service.workflow.impl;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.workflow.WorkflowAccessPolicy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Core: any active file may enter a workflow; everyone may view; the initiator manages. */
@Service
public class DefaultWorkflowAccessPolicy implements WorkflowAccessPolicy {

    @Override
    public Mono<Boolean> canStart(Document document, String userEmail) {
        return Mono.just(document != null && Boolean.TRUE.equals(document.getActive()) && document.getType() == DocumentType.FILE);
    }
}
