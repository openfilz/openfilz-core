package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

public interface MetadataPostProcessor {
    void process(Document document);
}
