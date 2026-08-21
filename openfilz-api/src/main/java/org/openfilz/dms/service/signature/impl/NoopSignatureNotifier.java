package org.openfilz.dms.service.signature.impl;

import org.openfilz.dms.service.signature.SignatureNotifier;
import org.springframework.stereotype.Service;

/** Core has no in-app notification centre; enterprise overrides with {@code NotificationService}. */
@Service
public class NoopSignatureNotifier implements SignatureNotifier {
}
