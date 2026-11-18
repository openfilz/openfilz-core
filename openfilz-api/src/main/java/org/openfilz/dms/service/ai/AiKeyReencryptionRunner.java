package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * After a rotation of {@code AI_SETTINGS_ENCRYPTION_KEY}: writes every stored AI secret again under the
 * current key, once the application is ready (so after Flyway) and in the background.
 * <p>
 * Runs only with AI on (the tables live in the AI migrations) and previous keys configured
 * ({@code AI_SETTINGS_ENCRYPTION_PREVIOUS_KEYS}). Each {@link AiSecretReencryption} logs its counts; a value
 * no key opens is counted and left alone; a database error is logged and never stops the application.
 * Two instances starting together are safe: every update is conditional on the value read. Once the
 * counts show nothing left to rewrite, the previous key can be removed at the next restart.
 */
@Slf4j
@Component
public class AiKeyReencryptionRunner {

    private final AiProperties ai;
    private final ObjectProvider<AiSettingsCipher> cipherProvider;
    private final ObjectProvider<AiSecretReencryption> reencryptions;

    public AiKeyReencryptionRunner(AiProperties ai, ObjectProvider<AiSettingsCipher> cipherProvider,
                                   ObjectProvider<AiSecretReencryption> reencryptions) {
        this.ai = ai;
        this.cipherProvider = cipherProvider;
        this.reencryptions = reencryptions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Mono<Void> pass = pass();
        if (pass != null) {
            pass.subscribeOn(Schedulers.boundedElastic()).subscribe();
        }
    }

    /** The pass to run, or null when there is nothing to do (AI off, no key, no previous key). */
    Mono<Void> pass() {
        if (!ai.isActive()) {
            return null;
        }
        AiSettingsCipher cipher;
        try {
            cipher = cipherProvider.getIfAvailable();
        } catch (RuntimeException e) {
            log.error("[AI-KEY-ROTATION] cannot read the AI settings keys, no re-encryption: {}", e.getMessage());
            return null;
        }
        if (cipher == null || !cipher.hasPreviousKeys()) {
            return null;
        }
        List<AiSecretReencryption> targets = reencryptions.orderedStream().toList();
        log.info("[AI-KEY-ROTATION] previous keys configured: rewriting stored AI secrets under key {} ({} table(s))",
                cipher.currentKid(), targets.size());
        return Flux.fromIterable(targets)
                .concatMap(target -> Mono.defer(() -> target.reencrypt(cipher))
                        .doOnNext(counts -> log.info("[AI-KEY-ROTATION] {}: {}", target.name(), counts))
                        .onErrorResume(e -> {
                            log.error("[AI-KEY-ROTATION] {}: pass failed, nothing more rewritten in this table until the next start: {}",
                                    target.name(), e.toString());
                            return Mono.empty();
                        }))
                .then();
    }
}
