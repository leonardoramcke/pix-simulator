package com.pixsim.transaction.infrastructure;

import com.pixsim.transaction.domain.PixTransaction;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * NOTA DE DESIGN: em produção real, publicar direto no Kafka dentro do
 * mesmo método @Transactional do banco é uma falha clássica de
 * "dual write" — se o commit do Postgres suceder mas o publish falhar
 * (ou vice-versa), os sistemas divergem.
 *
 * A solução correta é o padrão OUTBOX: gravar o evento em uma tabela
 * `outbox_events` DENTRO da mesma transação JPA, e um processo separado
 * (Debezium/CDC ou um poller) lê essa tabela e publica no Kafka de forma
 * assíncrona e garantida (at-least-once).
 *
 * Para manter este boilerplate simples, publicamos direto aqui — mas
 * o comentário serve como lembrete de que isso é uma simplificação
 * intencional para o estágio inicial do simulador. Evoluir para outbox
 * é o próximo passo natural de robustez do projeto.
 */
@Component
public class PixEventPublisher {

    private static final String TOPIC_COMPLETED = "pix.transaction.completed";
    private static final String TOPIC_FAILED = "pix.transaction.failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PixEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishTransactionCompleted(PixTransaction tx) {
        // chave de partição = conta de origem, garante ordenação por conta
        kafkaTemplate.send(TOPIC_COMPLETED, tx.getSourceAccountId().toString(), tx);
    }

    public void publishTransactionFailed(PixTransaction tx, String reason) {
        kafkaTemplate.send(TOPIC_FAILED, tx.getSourceAccountId().toString(), tx);
    }
}
