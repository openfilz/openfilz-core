package org.openfilz.dms.service.signature.impl;

import org.openfilz.dms.service.signature.SignatureCompletionListener;
import org.springframework.stereotype.Service;

/** Default listener: nothing to persist beyond the envelope itself. */
@Service
public class NoopSignatureCompletionListener implements SignatureCompletionListener {
}
