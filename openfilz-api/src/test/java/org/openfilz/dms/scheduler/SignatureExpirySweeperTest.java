package org.openfilz.dms.scheduler;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.service.SignatureService;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignatureExpirySweeperTest {

    private final SignatureService service = mock(SignatureService.class);
    private final SignatureProperties props = new SignatureProperties();
    private final SignatureExpirySweeper sweeper = new SignatureExpirySweeper(service, props);

    @Test
    void sweep_inactive_doesNothing() {
        props.setActive(false);
        sweeper.sweep();
        verifyNoInteractions(service);
    }

    @Test
    void sweep_active_subscribesToSweepExpired() {
        props.setActive(true);
        AtomicInteger subscriptions = new AtomicInteger();
        when(service.sweepExpired()).thenReturn(Mono.fromSupplier(() -> {
            subscriptions.incrementAndGet();
            return 3;
        }));

        sweeper.sweep();

        verify(service).sweepExpired();
        assertThat(subscriptions.get()).isEqualTo(1);
    }

    @Test
    void sweep_active_zeroExpired_stillCompletes() {
        props.setActive(true);
        when(service.sweepExpired()).thenReturn(Mono.just(0));
        assertThatCode(sweeper::sweep).doesNotThrowAnyException();
    }

    @Test
    void sweep_error_isSwallowed() {
        props.setActive(true);
        when(service.sweepExpired()).thenReturn(Mono.error(new IllegalStateException("db down")));
        assertThatCode(sweeper::sweep).doesNotThrowAnyException();
        verify(service).sweepExpired();
    }
}
