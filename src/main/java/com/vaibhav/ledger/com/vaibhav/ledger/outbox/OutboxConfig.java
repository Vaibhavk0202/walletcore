package com.vaibhav.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class OutboxConfig {

    static final String LEDGER_TRANSACTIONS_TOPIC = "ledger.transactions";

    /** Spring's KafkaAdmin creates this topic at startup if it does not exist yet. */
    @Bean
    NewTopic ledgerTransactionsTopic() {
        return TopicBuilder.name(LEDGER_TRANSACTIONS_TOPIC).partitions(3).replicas(1).build();
    }
}