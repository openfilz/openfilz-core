package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(@NotBlank @Size(min = 4, max = 12) String code) {}
