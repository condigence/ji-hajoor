package com.jihajoor.admin.sysconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/** System settings stored in a real (H2) database: insert, then update in place. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import(JpaSystemSettingStore.class)
class JpaSystemSettingStoreTest {

    @Autowired
    private JpaSystemSettingStore store;

    @Autowired
    private SystemSettingRepository repository;

    @Test
    void savesThenUpdatesASettingInPlace() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-10-25T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-25T11:00:00Z");

        store.save("audit.pageSize", "50", t1, first);
        store.save("audit.pageSize", "100", t2, second);

        assertThat(store.storedValues()).containsExactly(java.util.Map.entry("audit.pageSize", "100"));
        SystemSettingEntity row = repository.findById("audit.pageSize").orElseThrow();
        assertThat(row.getKey()).isEqualTo("audit.pageSize");
        assertThat(row.getValue()).isEqualTo("100");
        assertThat(row.getUpdatedAt()).isEqualTo(t2);
        assertThat(row.getUpdatedBy()).isEqualTo(second);
    }
}
