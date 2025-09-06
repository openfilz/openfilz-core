package org.openfilz.dms.dto.response;

import lombok.Builder;
import lombok.Getter;
import org.openfilz.dms.entity.PhysicalDocument;
import org.openfilz.dms.enums.DocumentType;

import java.util.UUID;

@Getter
@Builder
public class ChildElementInfo implements PhysicalDocument {

    private UUID id;
    private DocumentType type;
    private String name;
    private Long size;
    private String storagePath;
    private String path;


}
