package org.openfilz.dms.config;

public interface RestApiVersion {

    String API_VERSION = "v1";
    String API_PREFIX = "/api/" + API_VERSION;

    String ENDPOINT_AUDIT = "/audit";
    String ENDPOINT_DASHBOARD = "/dashboard";

    String ENDPOINT_DOCUMENTS = "/documents";
    String ENDPOINT_SUGGESTIONS = "/suggestions";
    String ENDPOINT_FAVORITES = "/favorites";
    String ENDPOINT_FILES = "/files";
    String ENDPOINT_FOLDERS = "/folders";
    String ENDPOINT_RECYCLE_BIN = "/recycle-bin";

    String ENDPOINT_ONLYOFFICE = "/onlyoffice";
    String ENDPOINT_THUMBNAILS = "/thumbnails";
}
