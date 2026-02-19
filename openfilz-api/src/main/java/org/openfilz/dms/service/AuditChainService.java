package org.openfilz.dms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
public class AuditChainService {

    private static final String GENESIS_SEED = "GENESIS";

    private final ObjectMapper sortedKeyMapper;
    private final String algorithm;

    public AuditChainService(AuditChainProperties properties) {
        this.algorithm = properties.getAlgorithm();
        this.sortedKeyMapper = new ObjectMapper();
        this.sortedKeyMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String computeGenesisHash() {
        return hash(GENESIS_SEED);
    }

    public String computeHash(OffsetDateTime timestamp, String userPrincipal, AuditAction action,
                              DocumentType resourceType, UUID resourceId,
                              IAuditLogDetails details, String previousHash) {
        String canonical = canonicalize(timestamp, userPrincipal, action, resourceType, resourceId, details, previousHash);
        return hash(canonical);
    }

    String canonicalize(OffsetDateTime timestamp, String userPrincipal, AuditAction action,
                        DocumentType resourceType, UUID resourceId,
                        IAuditLogDetails details, String previousHash) {
        String timestampStr = timestamp != null ? String.valueOf(timestamp.toInstant().toEpochMilli()) : "";
        String userStr = userPrincipal != null ? userPrincipal : "";
        String actionStr = action != null ? action.name() : "";
        String typeStr = resourceType != null ? resourceType.name() : "";
        String idStr = resourceId != null ? resourceId.toString() : "";
        String detailsStr = serializeDetails(details);
        String prevHashStr = previousHash != null ? previousHash : "";

        return timestampStr + "|" + userStr + "|" + actionStr + "|" + typeStr + "|" + idStr + "|" + detailsStr + "|" + prevHashStr;
    }

    private String serializeDetails(IAuditLogDetails details) {
        if (details == null) {
            return "";
        }
        try {
            return sortedKeyMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit details: {}", e.getMessage());
            return "";
        }
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Hash algorithm not available: " + algorithm, e);
        }
    }
}
