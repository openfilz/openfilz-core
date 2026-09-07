package org.openfilz.dms.service.workflow.impl;

import org.openfilz.dms.service.workflow.WorkflowCommentBridge;
import org.springframework.stereotype.Service;

/** Core has no threaded document comments; the history entry is the only record of a decision comment. */
@Service
public class NoopWorkflowCommentBridge implements WorkflowCommentBridge {
}
