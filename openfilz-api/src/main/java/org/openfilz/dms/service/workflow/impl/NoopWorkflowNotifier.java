package org.openfilz.dms.service.workflow.impl;

import org.openfilz.dms.service.workflow.WorkflowNotifier;
import org.springframework.stereotype.Service;

/** Core has no in-app notification system; the Enterprise Edition overrides this bean. */
@Service
public class NoopWorkflowNotifier implements WorkflowNotifier {
}
