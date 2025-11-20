package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.entity.SqlTableMapping.RECYCLE_BIN;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(RECYCLE_BIN)
public class RecycleBin {

    @Id
    @Column(ID)
    private UUID id;

    @Column(DELETED_AT)
    private OffsetDateTime deletedAt;

    @Column(DELETED_BY)
    private String deletedBy;

}
