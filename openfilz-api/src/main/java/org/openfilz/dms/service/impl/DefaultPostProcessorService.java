package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.PostProcessorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultPostProcessorService implements PostProcessorService {

    @Override
    public void process(FilePart filePart, Document document) {}
}
