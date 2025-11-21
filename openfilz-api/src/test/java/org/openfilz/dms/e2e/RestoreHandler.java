package org.openfilz.dms.e2e;

import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;

public record RestoreHandler(FolderResponse parent, UploadResponse file) {}