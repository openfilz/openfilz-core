package org.openfilz.dms.service.impl;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.DocumentPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;


@Service
@ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true)
public class DefaultDocumentPostProcessor implements DocumentPostProcessor {

    @Override
    public void process(FilePart filePart, Document document) {}
}
