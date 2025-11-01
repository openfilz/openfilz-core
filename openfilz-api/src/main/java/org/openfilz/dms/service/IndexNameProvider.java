package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

public interface IndexNameProvider {
    String getIndexName(Document document);
}
