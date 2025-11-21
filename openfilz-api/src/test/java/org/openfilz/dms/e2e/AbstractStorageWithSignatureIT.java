package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.MultipleUploadFileParameter;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

public abstract class AbstractStorageWithSignatureIT extends LocalStorageIT {


    public AbstractStorageWithSignatureIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    protected void checkFileInfo(UploadResponse uploadResponse, MultipleUploadFileParameter param, Map<String, Object> metadata, String checksum) {
        DocumentInfo info2 = getWebTestClient().get().uri(uri ->
                        uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                                .queryParam("withMetadata", true)
                                .build(uploadResponse.id()))
                .exchange()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();
        Assertions.assertNotNull(info2);
        Assertions.assertEquals(param.filename(), info2.name());
        Assertions.assertEquals(checksum, info2.metadata().get("sha256"));
        metadata.forEach((key, value) -> Assertions.assertEquals(value, info2.metadata().get(key)));

    }

    void whenDownloadDocumentMultiple_thenOk() {}

}
