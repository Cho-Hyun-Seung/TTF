package com.toki.ttf.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceSecurityTests {

    @Test
    void expiredWindowAllowsAnotherRequest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T00:00:00Z"));
        RateLimitService service = new RateLimitService(clock);

        service.check("login", "client", 1, Duration.ofSeconds(10));
        assertThatThrownBy(() -> service.check("login", "client", 1, Duration.ofSeconds(10)))
                .isInstanceOf(RateLimitService.RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(10));

        assertThatCode(() -> service.check("login", "client", 1, Duration.ofSeconds(10)))
                .doesNotThrowAnyException();
    }

    @Test
    void periodicMaintenanceEvictsExpiredWindows() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T00:00:00Z"));
        RateLimitService service = new RateLimitService(clock);
        Duration window = Duration.ofSeconds(1);

        for (int index = 0; index < 255; index++) {
            service.check("api", "expired-" + index, 1, window);
        }
        assertThat(windowCount(service)).isEqualTo(255);

        clock.advance(window);
        service.check("api", "current", 1, window);

        assertThat(windowCount(service)).isEqualTo(1);
    }

    private static int windowCount(RateLimitService service) throws Exception {
        Field windows = RateLimitService.class.getDeclaredField("windows");
        windows.setAccessible(true);
        return ((Map<?, ?>) windows.get(service)).size();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
