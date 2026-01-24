package org.openfilz.dms.service.impl;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true),
        @ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "false", matchIfMissing = true)
})
public class EmptyMetadataPostProcessor implements MetadataPostProcessor {

}
