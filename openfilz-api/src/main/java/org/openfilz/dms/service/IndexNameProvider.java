package org.openfilz.dms.service;

import org.openfilz.dms.entity.Document;

public interface IndexNameProvider {

    String DEFAULT_INDEX_NAME = "openfilz";

    String getIndexName(Document document);
}
