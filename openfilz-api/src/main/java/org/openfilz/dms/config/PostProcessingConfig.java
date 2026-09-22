package org.openfilz.dms.config;

import org.openfilz.dms.utils.BoundedTaskQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The queue that bounds the heavy background work started after an upload (text extraction and
 * indexing, thumbnails, embeddings). Without it, an unzip or a bulk upload of N files starts N of
 * each at once, starving the request itself and exhausting memory.
 */
@Configuration
public class PostProcessingConfig {

    public static final String POST_PROCESSING_QUEUE = "postProcessingQueue";

    /** {@code openfilz.post-processing.concurrency}; 0 or less = the number of CPUs (at least 2). */
    @Bean(name = POST_PROCESSING_QUEUE, destroyMethod = "dispose")
    public BoundedTaskQueue postProcessingQueue(@Value("${openfilz.post-processing.concurrency:0}") int concurrency) {
        int effective = concurrency > 0 ? concurrency : Math.max(2, Runtime.getRuntime().availableProcessors());
        return new BoundedTaskQueue("post-processing", effective);
    }
}
