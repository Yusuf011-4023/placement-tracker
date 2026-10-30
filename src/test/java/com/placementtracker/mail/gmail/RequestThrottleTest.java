package com.placementtracker.mail.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class RequestThrottleTest {

	private long now; // fake nanoTime
	private final List<Long> sleptMillis = new ArrayList<>();

	private RequestThrottle throttle(int perSecond) {
		return new RequestThrottle(perSecond, () -> now, nanos -> {
			sleptMillis.add(TimeUnit.NANOSECONDS.toMillis(nanos));
			now += nanos;
		});
	}

	@Test
	void throttleSpacesRequests() {
		RequestThrottle t = throttle(10); // one request per 100 ms

		for (int i = 0; i < 4; i++) {
			t.acquire();
		}

		assertThat(sleptMillis).containsExactly(100L, 100L, 100L); // the first goes straight through
	}

	@Test
	void idleTimeIsNotBanked() {
		RequestThrottle t = throttle(10);
		t.acquire();
		now += TimeUnit.SECONDS.toNanos(5); // a long pause must not allow a later burst

		t.acquire();
		t.acquire();

		assertThat(sleptMillis).containsExactly(100L);
	}
}
