package com.kgiannopoulou.ledgerforge.risk.infrastructure;

import com.kgiannopoulou.ledgerforge.messaging.infrastructure.ConsumedEvents;
import com.kgiannopoulou.ledgerforge.risk.application.RiskAssessmentService;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka adapter: decode, then hand over to the (transactional, idempotent) risk service. */
@Component
class RiskEventListener {

    private final ConsumedEvents events;
    private final RiskAssessmentService riskAssessment;

    RiskEventListener(ConsumedEvents events, RiskAssessmentService riskAssessment) {
        this.events = events;
        this.riskAssessment = riskAssessment;
    }

    @KafkaListener(id = "risk-engine", groupId = "ledgerforge-risk",
            topics = {EventTopics.TRANSFERS, EventTopics.PAYMENTS},
            autoStartup = "${ledgerforge.kafka.consumers.enabled:true}")
    void on(ConsumerRecord<String, String> record) {
        riskAssessment.assess(events.decode(record));
    }
}
