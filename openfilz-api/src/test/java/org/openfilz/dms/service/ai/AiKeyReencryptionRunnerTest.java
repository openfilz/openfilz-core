package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.openfilz.dms.utils.SecretReencryptionPass;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiKeyReencryptionRunnerTest {

    private static AiProperties ai(boolean active) {
        AiProperties ai = new AiProperties();
        ai.setActive(active);
        return ai;
    }

    private static AiSettingsCipher cipher(boolean previousKeys) {
        AiSettingsCipher cipher = mock(AiSettingsCipher.class);
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.hasPreviousKeys()).thenReturn(previousKeys);
        when(cipher.currentKid()).thenReturn("0a1b2c3d");
        return cipher;
    }

    private static AiSecretReencryption target(String name, Mono<SecretReencryptionPass.Counts> result, AtomicInteger calls) {
        return new AiSecretReencryption() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Mono<SecretReencryptionPass.Counts> reencrypt(AiSettingsCipher cipher) {
                calls.incrementAndGet();
                return result;
            }
        };
    }

    private static AiKeyReencryptionRunner runner(AiProperties ai, AiSettingsCipher cipher, AiSecretReencryption... targets) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (cipher != null) {
            beans.addBean("cipher", cipher);
        }
        for (int i = 0; i < targets.length; i++) {
            beans.addBean("target" + i, targets[i]);
        }
        ObjectProvider<AiSettingsCipher> cipherProvider = beans.getBeanProvider(AiSettingsCipher.class);
        ObjectProvider<AiSecretReencryption> targetProvider = beans.getBeanProvider(AiSecretReencryption.class);
        return new AiKeyReencryptionRunner(ai, cipherProvider, targetProvider);
    }

    @Test
    void aiOff_nothingRuns() {
        assertNull(runner(ai(false), cipher(true)).pass());
    }

    @Test
    void noPreviousKey_nothingRuns() {
        assertNull(runner(ai(true), cipher(false)).pass());
        assertNull(runner(ai(true), null).pass());
    }

    @Test
    void aFailingTable_isLoggedAndTheOthersStillRun() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AiKeyReencryptionRunner runner = runner(ai(true), cipher(true),
                target("broken", Mono.error(new IllegalStateException("relation does not exist")), first),
                target("ok", Mono.just(new SecretReencryptionPass.Counts(2, 1, 0, 0)), second));

        StepVerifier.create(runner.pass()).verifyComplete();

        org.junit.jupiter.api.Assertions.assertEquals(1, first.get());
        org.junit.jupiter.api.Assertions.assertEquals(1, second.get());
    }

    @Test
    void aTableThatThrowsWhileBuildingItsPass_doesNotStopTheRun() {
        AtomicInteger calls = new AtomicInteger();
        AiSecretReencryption throwing = new AiSecretReencryption() {
            @Override
            public String name() {
                return "throwing";
            }

            @Override
            public Mono<SecretReencryptionPass.Counts> reencrypt(AiSettingsCipher cipher) {
                throw new IllegalStateException("no database");
            }
        };
        AiKeyReencryptionRunner runner = runner(ai(true), cipher(true), throwing,
                target("ok", Mono.just(SecretReencryptionPass.Counts.NONE), calls));

        StepVerifier.create(runner.pass()).verifyComplete();
        org.junit.jupiter.api.Assertions.assertEquals(1, calls.get());
    }
}
