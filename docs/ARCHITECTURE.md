# Arquitetura

## Visão geral

O OrderFlow é um **monólito modular** em Spring Boot com integração real ao Apache Kafka. Não é um sistema de
microsserviços: há um único backend implantável. O Kafka separa *quando* cada etapa acontece (criação do pedido
× pagamento), não em quais serviços ela roda. A divisão em módulos deixa claro onde cada fronteira estaria se um
dia o pagamento virasse um serviço independente.

```mermaid
flowchart LR
    subgraph Browser
        UI[Angular 22 SPA]
    end
    subgraph Docker Compose
        NG[Nginx<br/>estáticos + proxy /api]
        subgraph Backend[Spring Boot 4.1 · Java 21]
            WEB[REST controllers<br/>Spring Security]
            APP[Serviços de aplicação<br/>OrderService · ProductService]
            RELAY[Outbox relay<br/>@Scheduled]
            CONS[Consumidores Kafka<br/>pagamento · DLT]
            GW[Gateway de pagamento<br/>SIMULADO]
        end
        PG[(PostgreSQL 18)]
        K[[Kafka 4.3 · KRaft]]
    end
    UI -->|cookie de sessão HttpOnly + X-XSRF-TOKEN| NG --> WEB --> APP
    APP -->|pedido + reserva + evento<br/>na mesma transação| PG
    RELAY -->|lê outbox<br/>FOR UPDATE SKIP LOCKED| PG
    RELAY -->|orderflow.order-events| K
    K --> CONS
    CONS --> GW
    CONS -->|status, baixa/liberação de estoque,<br/>novo evento no outbox| PG
    CONS -.->|falha definitiva| K
```

## Módulos

| Pacote | Responsabilidade | Camadas |
|---|---|---|
| `catalog` | Produtos e saldo de estoque (disponível, reservado), movimentações | domain · application · infrastructure · web |
| `ordering` | Pedido (agregado), itens com preço congelado, máquina de estados, histórico | domain · application · infrastructure · web |
| `payment` | Port `PaymentGateway`, adapter simulado, processamento idempotente do pagamento | port + adapter + serviço |
| `messaging` | Outbox, relay para o Kafka, consumidores, idempotência, log de processamento, DLT | infraestrutura de integração |
| `auth` | Usuários, BCrypt, sessão, CSRF, CORS, papéis | segurança |
| `dashboard` | Indicadores consultados no banco | leitura |
| `demo` | Seed opcional de usuários e produtos `DEMO-*` | dados de demonstração |

**Ports e adapters onde há troca real de tecnologia:** `OrderEventPublisher` (aplicação) é implementado pelo outbox;
`PaymentGateway` (aplicação) é implementado pelo gateway simulado. O domínio (`Order`, `Product`, `OrderStatus`) não
conhece Spring, Kafka nem HTTP, só JPA para mapeamento. Não criei interfaces para repositórios: o Spring Data já
é a abstração, e uma camada extra só duplicaria código.

## Fluxo de um pedido

```mermaid
sequenceDiagram
    autonumber
    actor U as Operador
    participant API as OrderController
    participant S as OrderService
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant K as Kafka
    participant C as PaymentEventsListener
    participant P as PaymentProcessingService
    participant G as Gateway simulado

    U->>API: POST /api/orders (Idempotency-Key)
    API->>S: create()
    S->>DB: SELECT produtos FOR UPDATE (ordem por id)
    S->>DB: reserva estoque, grava pedido + itens + histórico
    S->>DB: INSERT outbox (ORDER_CREATED)
    S-->>API: 201 PENDING_PAYMENT (commit único)
    loop a cada 500 ms
        R->>DB: SELECT pendentes FOR UPDATE SKIP LOCKED
        R->>K: send(key=orderId) e aguarda ack
        R->>DB: marca published_at
    end
    K->>C: ORDER_CREATED
    C->>P: processOrderCreated()
    P->>DB: SELECT pedido FOR UPDATE + já processado?
    P->>G: charge()
    alt aprovado
        P->>DB: PAID, reserva vira consumo, ORDER_PAID no outbox, processed_message
    else recusado
        P->>DB: PAYMENT_DECLINED, reserva liberada, evento, processed_message
    else falha temporária
        G-->>C: TransientPaymentException
        C->>DB: log RETRYING (transação própria)
        Note over C,K: DefaultErrorHandler: backoff fixo, até 3 retentativas
        K->>C: reentrega
    end
    Note over K: esgotou as retentativas → orderflow.order-events.DLT
    K->>C: DLT
    C->>P: processDeadLetter()
    P->>DB: PAYMENT_FAILED, reserva liberada (compensação)
```

## Máquina de estados

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT: pedido criado, estoque reservado
    PENDING_PAYMENT --> PAID: pagamento aprovado (reserva consumida)
    PENDING_PAYMENT --> PAYMENT_DECLINED: recusado (reserva liberada)
    PENDING_PAYMENT --> PAYMENT_FAILED: DLT após retentativas (reserva liberada)
    PENDING_PAYMENT --> CANCELLED: cancelado (reserva liberada)
    PAID --> CANCELLED: estorno simulado (itens devolvidos ao estoque)
    PAYMENT_DECLINED --> [*]
    PAYMENT_FAILED --> [*]
    CANCELLED --> [*]
```

Toda mudança passa por `Order.transitionTo`, que valida a transição com `OrderStatus.canTransitionTo` e registra o
histórico. Transições inválidas lançam `DomainException(INVALID_TRANSITION)` sem alterar o pedido.

## Modelo de dados

```mermaid
erDiagram
    product ||--o{ stock_movement : "movimenta"
    product ||--o{ order_item : "vendido em"
    customer_order ||--|{ order_item : "contém"
    customer_order ||--|{ order_status_history : "registra"
    customer_order ||--o{ outbox_event : "gera (aggregate_id)"
    outbox_event ||--o{ processing_log : "consumido (event_id)"
    outbox_event ||--o| processed_message : "idempotência (event_id)"
    customer_order ||--o{ payment_attempt : "tentativas (order_id)"

    product {
        bigint id PK
        varchar sku UK
        numeric price
        int available_quantity
        int reserved_quantity
        bigint version
    }
    customer_order {
        bigint id PK
        varchar status
        varchar payment_scenario
        numeric total_amount
        varchar idempotency_key UK
        bigint version
    }
    order_item {
        bigint id PK
        bigint product_id
        numeric unit_price
        int quantity
        numeric line_total
    }
    outbox_event {
        uuid id PK
        varchar event_type
        jsonb payload
        timestamptz published_at
    }
    processed_message {
        varchar consumer PK
        uuid event_id PK
    }
```

Regras críticas também existem no banco: `CHECK (available_quantity >= 0)`, `CHECK (price >= 0)`, `UNIQUE (sku)`,
`UNIQUE (idempotency_key)`, `UNIQUE (order_id, product_id)` e a PK composta de `processed_message`.

## Segurança

- Sessão por cookie `JSESSIONID` **HttpOnly** e `SameSite=Lax`; nenhum token em `localStorage`.
- CSRF em modo SPA: cookie `XSRF-TOKEN` legível + header `X-XSRF-TOKEN` em toda escrita.
- Senhas com BCrypt (`DelegatingPasswordEncoder`); mensagem de login genérica; e-mail não vai para o log.
- Autorização por papel (`ADMIN`, `OPERATOR`) com `@PreAuthorize`; operador só cancela os próprios pedidos.
- Controle otimista de versão na edição de produto (409 em edição concorrente).
- Erros em RFC 9457, sem stack trace; CSP, `X-Frame-Options`, `Referrer-Policy` no backend e no Nginx.
- Em Docker, frontend e API ficam na mesma origem (Nginx). O CORS só libera `localhost:4200` para desenvolvimento,
  e o backend usa `X-Forwarded-Host` para reconhecer a origem real atrás do proxy.
- CI com varredura de segredos (gitleaks) e `npm audit`; Dependabot para Maven, npm, Docker e Actions.
