-- OrderFlow: esquema inicial.
-- Regras críticas também ficam no banco (CHECK/UNIQUE) como última linha de defesa.

create table app_user (
    id            bigserial primary key,
    email         varchar(160) not null unique,
    full_name     varchar(120) not null,
    password_hash varchar(100) not null,
    role          varchar(20)  not null check (role in ('ADMIN', 'OPERATOR')),
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now()
);

create table product (
    id                  bigserial primary key,
    sku                 varchar(40)    not null unique,
    name                varchar(120)   not null,
    description         varchar(500),
    price               numeric(12, 2) not null check (price >= 0),
    status              varchar(20)    not null check (status in ('ACTIVE', 'INACTIVE')),
    available_quantity  integer        not null check (available_quantity >= 0),
    reserved_quantity   integer        not null default 0 check (reserved_quantity >= 0),
    low_stock_threshold integer        not null default 5 check (low_stock_threshold >= 0),
    version             bigint         not null default 0,
    created_at          timestamptz    not null,
    updated_at          timestamptz    not null
);

create table stock_movement (
    id         bigserial primary key,
    product_id bigint       not null references product (id),
    order_id   bigint,
    type       varchar(20)  not null check (type in ('INITIAL', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'CONSUME', 'RETURN')),
    quantity   integer      not null,
    reason     varchar(200),
    created_by varchar(160) not null,
    created_at timestamptz  not null
);
create index ix_stock_movement_product on stock_movement (product_id, created_at desc);

create table customer_order (
    id               bigserial primary key,
    customer_name    varchar(120)   not null,
    customer_email   varchar(160)   not null,
    status           varchar(30)    not null check (status in ('PENDING_PAYMENT', 'PAID', 'PAYMENT_DECLINED', 'PAYMENT_FAILED', 'CANCELLED')),
    payment_scenario varchar(30)    not null check (payment_scenario in ('APPROVE', 'DECLINE', 'TRANSIENT_FAILURE', 'GATEWAY_DOWN')),
    total_amount     numeric(14, 2) not null check (total_amount >= 0),
    idempotency_key  varchar(80) unique,
    created_by       varchar(160)   not null,
    version          bigint         not null default 0,
    created_at       timestamptz    not null,
    updated_at       timestamptz    not null
);
create index ix_order_status_created on customer_order (status, created_at desc);
create index ix_order_created on customer_order (created_at desc);

create table order_item (
    id           bigserial primary key,
    order_id     bigint         not null references customer_order (id) on delete cascade,
    product_id   bigint         not null references product (id),
    sku          varchar(40)    not null,
    product_name varchar(120)   not null,
    unit_price   numeric(12, 2) not null check (unit_price >= 0),
    quantity     integer        not null check (quantity > 0),
    line_total   numeric(14, 2) not null check (line_total >= 0),
    unique (order_id, product_id)
);

create table order_status_history (
    id          bigserial primary key,
    order_id    bigint       not null references customer_order (id) on delete cascade,
    from_status varchar(30),
    to_status   varchar(30)  not null,
    reason      varchar(300),
    changed_by  varchar(160) not null,
    changed_at  timestamptz  not null
);
create index ix_history_order on order_status_history (order_id, changed_at);

-- Transactional Outbox: gravado na mesma transação do pedido, publicado no Kafka por um relay.
create table outbox_event (
    id               uuid primary key,
    aggregate_type   varchar(40)  not null,
    aggregate_id     varchar(40)  not null,
    event_type       varchar(60)  not null,
    payload          jsonb        not null,
    created_at       timestamptz  not null,
    published_at     timestamptz,
    publish_attempts integer      not null default 0,
    last_error       varchar(500)
);
create index ix_outbox_pending on outbox_event (created_at) where published_at is null;
create index ix_outbox_aggregate on outbox_event (aggregate_type, aggregate_id);

-- Idempotência do consumidor: um evento só produz efeito uma vez por consumidor.
create table processed_message (
    consumer     varchar(60) not null,
    event_id     uuid        not null,
    processed_at timestamptz not null,
    primary key (consumer, event_id)
);

-- Registro de cada tentativa de processamento (visível na tela de histórico).
create table processing_log (
    id         bigserial primary key,
    event_id   uuid         not null,
    event_type varchar(60)  not null,
    order_id   bigint,
    consumer   varchar(60)  not null,
    outcome    varchar(30)  not null check (outcome in ('PROCESSED', 'DUPLICATE_IGNORED', 'SKIPPED', 'RETRYING', 'DEAD_LETTERED')),
    attempt    integer      not null,
    detail     varchar(500),
    created_at timestamptz  not null
);
create index ix_processing_log_order on processing_log (order_id, created_at);
create index ix_processing_log_created on processing_log (created_at desc);

-- Tentativas do gateway de pagamento SIMULADO (nenhum dado de cartão é armazenado).
-- Sem FK para customer_order de propósito: a tentativa é gravada em transação própria (REQUIRES_NEW)
-- enquanto a transação do consumidor mantém FOR UPDATE no pedido; uma FK faria a gravação esperar
-- por esse lock (auto-deadlock). processing_log segue a mesma regra.
create table payment_attempt (
    id                  bigserial primary key,
    order_id            bigint      not null,
    attempt             integer     not null,
    outcome             varchar(30) not null check (outcome in ('APPROVED', 'DECLINED', 'TRANSIENT_FAILURE')),
    simulated_reference varchar(40),
    created_at          timestamptz not null
);
create index ix_payment_attempt_order on payment_attempt (order_id);
