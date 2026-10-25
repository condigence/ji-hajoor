package com.jihajoor.customer.geocoding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoopGeocodingAdapterTest {

    @Test
    void resolvesNothing() {
        assertThat(new NoopGeocodingAdapter().reverseGeocode(25.5, 84.6)).isEmpty();
    }
}
