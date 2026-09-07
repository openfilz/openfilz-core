package org.openfilz.dms.dto.workflow;

import java.util.Map;

/**
 * SQL restriction applied to every <em>listing</em> of workflow instances, so the rows, the total
 * and the summary counters agree on what the caller may see.
 * <p>
 * {@link org.openfilz.dms.service.workflow.WorkflowAccessPolicy#visibleInstances(String)} answers
 * with one of these: core has no per-document permissions and returns {@link #ALL}; the Enterprise
 * Edition returns its ownership / share predicate. Filtering in SQL rather than over the fetched
 * page is deliberate — a post-filter would leave short pages and a total counting rows the caller
 * is not allowed to know about.
 * <p>
 * {@code predicate} is a fragment starting with {@code AND}, in which the token {@value #DOCUMENT_ID}
 * is replaced by the document-id column of the query it is spliced into (the instance table is
 * aliased differently depending on the query). {@code binds} are its named parameters; use names
 * unlikely to collide with the query's own (the enterprise ones are prefixed {@code wf}).
 */
public record WorkflowInstanceScope(String predicate, Map<String, Object> binds) {

    /** Token replaced by the document-id column expression of the host query. */
    public static final String DOCUMENT_ID = "{documentId}";

    /** No restriction — every instance is visible (the core answer). */
    public static final WorkflowInstanceScope ALL = new WorkflowInstanceScope("", Map.of());

    public WorkflowInstanceScope {
        predicate = predicate == null ? "" : predicate;
        binds = binds == null ? Map.of() : Map.copyOf(binds);
    }

    public boolean restricts() {
        return !predicate.isBlank();
    }

    /** The fragment ready to splice, with {@link #DOCUMENT_ID} pointing at {@code documentIdColumn}. */
    public String sql(String documentIdColumn) {
        return predicate.replace(DOCUMENT_ID, documentIdColumn);
    }
}
