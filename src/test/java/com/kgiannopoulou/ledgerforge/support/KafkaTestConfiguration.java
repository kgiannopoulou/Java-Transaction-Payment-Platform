package com.kgiannopoulou.ledgerforge.support;

import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookEndpointRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfiguration {

    @Bean
    @Primary
    FlakyWebhookEndpointRepository flakyWebhookEndpoints(
            @Qualifier("jdbcWebhookEndpointRepository") WebhookEndpointRepository real) {
        return new FlakyWebhookEndpointRepository(real);
    }
}
