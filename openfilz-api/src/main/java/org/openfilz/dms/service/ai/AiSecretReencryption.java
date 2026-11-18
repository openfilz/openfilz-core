package org.openfilz.dms.service.ai;

import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.openfilz.dms.utils.SecretReencryptionPass;
import reactor.core.publisher.Mono;

/**
 * A table holding secrets under {@link AiSettingsCipher}, rewritten under the current key by the start-up
 * pass ({@link AiKeyReencryptionRunner}) when previous keys are configured.
 * <p>
 * Core registers one for {@code user_ai_settings}. An extension that stores other secrets under the same
 * cipher adds a bean of its own; nothing else to wire. Implementations use a conditional update
 * ({@code WHERE <column> = <old value>}) so two instances starting together do not overwrite each other,
 * leave a row that no key opens untouched and count it, and never throw for one row.
 */
public interface AiSecretReencryption {

    /** Short name for the log line, e.g. the table. */
    String name();

    /** Rewrites every value not under the current key. */
    Mono<SecretReencryptionPass.Counts> reencrypt(AiSettingsCipher cipher);
}
