package org.openfilz.dms.service.ai;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Core default: any {@code http://} or {@code https://} address, a server on the local network included. */
@Component
public class SchemeOnlyAiBaseUrlPolicy implements AiBaseUrlPolicy {

    @Override
    public String validate(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl must start with http:// or https://");
        }
        return url;
    }
}
