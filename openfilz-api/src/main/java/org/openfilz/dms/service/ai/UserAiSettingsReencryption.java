package org.openfilz.dms.service.ai;

import org.openfilz.dms.exception.AiKeyUnreadableException;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.openfilz.dms.utils.SecretReencryptionPass;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** The personal API keys ({@code user_ai_settings.api_key_encrypted}, AI migrations: exists only with AI on). */
@Component
@Lazy
public class UserAiSettingsReencryption implements AiSecretReencryption {

    static final String SELECT = "SELECT user_email, api_key_encrypted FROM user_ai_settings WHERE api_key_encrypted IS NOT NULL";
    static final String UPDATE = "UPDATE user_ai_settings SET api_key_encrypted = :next "
            + "WHERE user_email = :id AND api_key_encrypted = :previous";

    private final DatabaseClient databaseClient;

    public UserAiSettingsReencryption(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public String name() {
        return "user_ai_settings";
    }

    @Override
    public Mono<SecretReencryptionPass.Counts> reencrypt(AiSettingsCipher cipher) {
        return SecretReencryptionPass.run(
                databaseClient.sql(SELECT)
                        .map((row, meta) -> new SecretReencryptionPass.Row(
                                row.get("user_email", String.class), row.get("api_key_encrypted", String.class)))
                        .all(),
                cipher::reencrypt,
                AiKeyUnreadableException.class::isInstance,
                (id, previous, next) -> databaseClient.sql(UPDATE)
                        .bind("next", next)
                        .bind("id", id)
                        .bind("previous", previous)
                        .fetch().rowsUpdated());
    }
}
