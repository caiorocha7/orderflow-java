# Decisões técnicas e trade-offs

## 1. Monólito modular em vez de microsserviços

**Decisão:** um único backend Spring Boot, dividido em módulos (`catalog`, `ordering`, `payment`, `messaging`).
**Por quê:** o domínio cabe em uma equipe e um banco. Separar em serviços traria rede, deploy e consistência
distribuída sem benefício real neste escopo.
**Trade-off:** o pagamento escala junto com a API. A fronteira já existe (port `PaymentGateway` e consumo via Kafka),
então extrair um serviço exigiria mover o consumidor, não reescrever o fluxo.

## 2. Reserva de estoque com lock pessimista (`SELECT ... FOR UPDATE`)

**Decisão:** ao criar o pedido, as linhas dos produtos são bloqueadas **sempre em ordem de id** antes de validar e
reservar.
**Por quê:** "verificar saldo e depois reservar" é uma condição de corrida clássica. Com lock otimista, sob disputa
muitas requisições falhariam e precisariam de retry; com `UPDATE ... WHERE available >= qty` o código ficaria
correto mas espalharia a regra entre SQL e domínio.
**Trade-off:** pedidos pelo mesmo produto são serializados (latência sob disputa alta). A ordem fixa evita
deadlock entre pedidos com produtos em ordem inversa (há teste para isso). O `CHECK (available_quantity >= 0)` é a
última linha de defesa.

## 3. Transactional Outbox em vez de publicar direto no Kafka

**Decisão:** o evento `ORDER_CREATED` é gravado na tabela `outbox_event` na mesma transação do pedido. Um relay
agendado publica no Kafka e marca como publicado.
**Por quê:** publicar dentro da transação arrisca evento sem pedido (rollback depois do envio); publicar depois do
commit arrisca pedido sem evento (queda entre o commit e o envio).
**Trade-off:** latência de até ~500 ms (intervalo do relay) e entrega **at-least-once**: o mesmo evento pode chegar
duas vezes. Por isso a decisão 4. O relay usa `FOR UPDATE SKIP LOCKED`, então várias instâncias podem rodar sem
publicar o mesmo evento em paralelo. Alternativa considerada: CDC com Debezium, mais robusta, mas exigiria Kafka
Connect e mais infraestrutura do que o projeto precisa.

## 4. Idempotência no consumidor por `eventId`

**Decisão:** tabela `processed_message (consumer, event_id)` com PK composta, gravada **na mesma transação** do efeito
(status, estoque, novo evento). A checagem acontece depois de bloquear o pedido.
**Por quê:** o Kafka e o outbox garantem pelo menos uma entrega, não exatamente uma. Com a marca na mesma transação,
ou o efeito e a marca existem juntos, ou nenhum dos dois.
**Trade-off:** uma escrita a mais por mensagem e uma tabela que cresce (em produção, com limpeza por data).

## 5. Retry com backoff e Dead Letter Topic

**Decisão:** `DefaultErrorHandler` do Spring Kafka com backoff fixo (3 retentativas por padrão) para
`TransientPaymentException`; depois, `DeadLetterPublishingRecoverer` envia para `orderflow.order-events.DLT`. Um
consumidor da DLT faz a compensação: marca `PAYMENT_FAILED` e libera a reserva. Mensagens malformadas e violações de
regra não são retentadas.
**Por quê:** falha temporária não deve virar recusa; falha definitiva não pode prender o estoque para sempre.
**Trade-off:** retentativas bloqueantes seguram a partição durante o backoff (alguns segundos). Para volumes maiores,
tópicos de retry não bloqueantes (`@RetryableTopic`) seriam o próximo passo. Cada tentativa é registrada em transação
própria (`REQUIRES_NEW`), por isso `payment_attempt` e `processing_log` não têm FK para o pedido: a FK faria essa
gravação esperar pelo lock da transação externa.

## 6. Pagamento simulado e escolhido por pedido

**Decisão:** o gateway é uma implementação fictícia do port `PaymentGateway`. O cenário (`APPROVE`, `DECLINE`,
`TRANSIENT_FAILURE`, `GATEWAY_DOWN`) é escolhido na criação do pedido.
**Por quê:** deixa cada caminho do fluxo demonstrável e testável de forma determinística, sem integração real nem
dados de cartão.
**Trade-off:** em um sistema real o cenário não viria do cliente; o adapter seria trocado por um gateway de verdade,
sem mudar o restante.

## 7. Sessão com cookie HttpOnly + CSRF, em vez de JWT no navegador

**Decisão:** login cria uma sessão no servidor; o navegador guarda só o cookie HttpOnly. Escritas exigem token CSRF.
**Por quê:** token em `localStorage` fica exposto a XSS. Para uma SPA servida na mesma origem, sessão é mais simples e
segura.
**Trade-off:** estado de sessão no servidor (uma instância). Para várias instâncias: Spring Session com Redis ou
PostgreSQL.

## 8. Snapshot de preço no item

**Decisão:** o item guarda SKU, nome e preço no momento da compra; o total é calculado no backend.
**Por quê:** mudar o preço do produto depois não pode alterar pedidos existentes, e o cliente nunca define valores.

## 9. Idempotency-Key na criação do pedido

**Decisão:** header opcional `Idempotency-Key` com `UNIQUE` no banco. Repetições devolvem o pedido original (200).
**Por quê:** duplo clique ou retry de rede não podem criar dois pedidos nem reservar estoque duas vezes. Duas
requisições simultâneas com a mesma chave: a segunda perde no `UNIQUE` e recebe o pedido da primeira.

## 10. Testcontainers em vez de banco em memória e Kafka embutido

**Decisão:** testes de integração usam PostgreSQL 18 e Kafka 4.3 reais em contêineres.
**Por quê:** `FOR UPDATE`, `SKIP LOCKED`, `jsonb` e o comportamento de reentrega do Kafka não existem (ou mudam) em
H2 e em brokers embutidos. Os testes de concorrência só provam algo contra o banco real.
**Trade-off:** os testes exigem Docker e levam ~30 s para subir os contêineres (compartilhados entre as classes).
