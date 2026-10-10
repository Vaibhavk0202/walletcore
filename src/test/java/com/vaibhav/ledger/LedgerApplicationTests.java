package com.vaibhav.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InfrastructureSmokeTest {

	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired KafkaTemplate<String, String> kafka;

	@Test
	void flywayAppliedBaselineMigration() {
		Integer applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where success", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(1);
	}

	@Test
	void redisRoundTrip() {
		redis.opsForValue().set("phase0:ping", "pong");
		assertThat(redis.opsForValue().get("phase0:ping")).isEqualTo("pong");
	}

	@Test
	void kafkaAcceptsAMessage() throws Exception {
		var result = kafka.send("phase0.smoke", "key", "hello").get(30, TimeUnit.SECONDS);
		assertThat(result.getRecordMetadata().offset()).isGreaterThanOrEqualTo(0);
	}
}