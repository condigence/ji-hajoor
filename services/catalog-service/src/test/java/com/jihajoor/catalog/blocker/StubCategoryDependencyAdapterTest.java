package com.jihajoor.catalog.blocker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class StubCategoryDependencyAdapterTest {

    @Test
    void reportsNoBlockers() {
        assertThat(new StubCategoryDependencyAdapter().findBlockers(UUID.randomUUID()))
                .isEqualTo(CategoryBlockers.none());
    }
}
