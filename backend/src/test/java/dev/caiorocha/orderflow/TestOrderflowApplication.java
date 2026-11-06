package dev.caiorocha.orderflow;

import org.springframework.boot.SpringApplication;

/** Sobe a aplicação localmente com PostgreSQL e Kafka em contêineres (útil para desenvolvimento). */
public class TestOrderflowApplication {

    public static void main(String[] args) {
        SpringApplication.from(OrderflowApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
