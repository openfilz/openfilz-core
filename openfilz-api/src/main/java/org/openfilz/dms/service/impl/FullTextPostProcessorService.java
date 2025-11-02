package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.FullTextService;
import org.openfilz.dms.service.PostProcessorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "true")
public class FullTextPostProcessorService implements PostProcessorService {

    protected final FullTextService fullTextService;

    @Override
    public void process(FilePart filePart, Document document) {
        fullTextService.process(filePart, document);
    }
}
