package dev.caiorocha.orderflow.ordering.domain;

/**
 * Cenário do pagamento SIMULADO, escolhido na criação do pedido para demonstrar cada caminho.
 * Não existe integração com meio de pagamento real.
 */
public enum PaymentScenario {
    /** Aprovado na primeira tentativa. */
    APPROVE,
    /** Recusado pelo emissor simulado (resultado de negócio, sem retentativa). */
    DECLINE,
    /** Falha temporária nas duas primeiras tentativas; aprovado na terceira (demonstra retry). */
    TRANSIENT_FAILURE,
    /** Gateway indisponível em todas as tentativas: esgota o retry e vai para a DLT. */
    GATEWAY_DOWN
}
