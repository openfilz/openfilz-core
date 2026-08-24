package org.openfilz.dms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureServiceActorTest {

    @Test
    void of_uuidSubject_isUsedAsId() {
        UUID sub = UUID.randomUUID();
        SignatureService.Actor actor = SignatureService.Actor.of(sub.toString(), "Alice@Example.COM");
        assertThat(actor.id()).isEqualTo(sub);
        assertThat(actor.email()).isEqualTo("alice@example.com");
    }

    @Test
    void of_nonUuidSubject_derivesDeterministicNameUuidFromEmail() {
        SignatureService.Actor a = SignatureService.Actor.of("john.doe", "John@Example.com");
        SignatureService.Actor b = SignatureService.Actor.of("another-subject", "john@example.com");
        UUID expected = UUID.nameUUIDFromBytes("openfilz:john@example.com".getBytes(StandardCharsets.UTF_8));
        assertThat(a.id()).isEqualTo(expected);
        assertThat(b.id()).isEqualTo(expected);
        assertThat(a.email()).isEqualTo("john@example.com");
    }

    @Test
    void of_nullSubject_fallsBackToNameUuid() {
        SignatureService.Actor a = SignatureService.Actor.of(null, "x@y.z");
        assertThat(a.id()).isEqualTo(UUID.nameUUIDFromBytes("openfilz:x@y.z".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void of_nullEmail_keepsNullEmail_andStillBuildsAnId() {
        SignatureService.Actor a = SignatureService.Actor.of("not-a-uuid", null);
        assertThat(a.email()).isNull();
        assertThat(a.id()).isEqualTo(UUID.nameUUIDFromBytes("openfilz:null".getBytes(StandardCharsets.UTF_8)));

        SignatureService.Actor b = SignatureService.Actor.of(UUID.randomUUID().toString(), null);
        assertThat(b.email()).isNull();
        assertThat(b.id()).isNotNull();
    }

    @Test
    void of_differentEmails_differentIds() {
        assertThat(SignatureService.Actor.of("s", "a@x.io").id())
                .isNotEqualTo(SignatureService.Actor.of("s", "b@x.io").id());
    }
}
