package com.jihajoor.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AppConfigClientControllerTest {

    @Test
    void servesOnlyTheRowsExposedToClients() throws SQLException {
        String url = AppConfigEnvironmentPostProcessorTest.database();
        AppConfigEnvironmentPostProcessorTest.execute(url,
                "INSERT INTO config.app_config (config_key, config_value, exposed_to_clients) VALUES "
                        + "('jihajoor.apps.provider-app-url', 'https://pro.example', true), "
                        + "('jihajoor.apps.customer-app-url', 'https://app.example', true), "
                        + "('jihajoor.internal.setting', 'server-only', false)");
        AppConfigClientController controller =
                new AppConfigClientController(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));

        ResponseEntity<Map<String, String>> response = controller.clientConfig();

        assertThat(response.getBody()).containsExactly(
                Map.entry("jihajoor.apps.customer-app-url", "https://app.example"),
                Map.entry("jihajoor.apps.provider-app-url", "https://pro.example"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=300, public");
    }
}
