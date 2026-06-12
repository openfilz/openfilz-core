package org.openfilz.dms.dto.response;

import org.springframework.core.io.Resource;

/**
 * A specific version of a file document, ready to be streamed to the client.
 */
public record DownloadableVersion(String fileName, String contentType, Resource resource) {
}
