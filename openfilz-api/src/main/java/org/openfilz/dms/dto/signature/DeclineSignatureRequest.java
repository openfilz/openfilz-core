package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.Size;

public record DeclineSignatureRequest(@Size(max = 1000) String reason) {}
