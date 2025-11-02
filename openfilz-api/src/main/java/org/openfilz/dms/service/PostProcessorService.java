package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;
import org.springframework.http.codec.multipart.FilePart;

public interface PostProcessorService {
    void process(FilePart filePart, Document document);
}
