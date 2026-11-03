package com.kgiannopoulou.ledgerforge.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfiguration {

    @Bean
    @Primary
    RecordingWebhookSender recordingWebhookSender() {
        return new RecordingWebhookSender();
    }
}
