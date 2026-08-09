package com.pixsim.notification.consumer;

import com.pixsim.notification.dto.PixTransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consome os eventos publicados pelo transaction-service e simula o envio
 * de notificações ao usuário (push, SMS, e-mail — em um sistema real, aqui
 * entraria a integração com um provedor externo).
 *
 * Por que um serviço SEPARADO em vez de lógica dentro do transaction-service:
 *   1. Isolamento de falha: se o envio de notificação falhar ou ficar
 *      lento (ex: provedor de SMS fora do ar), isso NUNCA deve travar ou
 *      atrasar uma transferência PIX — dinheiro é crítico, notificação não.
 *   2. Escalabilidade independente: em pico de uso, notificação pode
 *      precisar de mais réplicas que o serviço de transação, ou vice-versa.
 *   3. Deploy independente: mudanças no formato de notificação não exigem
 *      re-deploy do serviço financeiro core.
 *
 * Cada método está em um "consumer group" próprio (definido no
 * application.yml), permitindo que múltiplos serviços consumam os MESMOS
 * eventos de forma independente (ex: fraud-service também poderia
 * consumir 'pix.transaction.completed' sem interferir neste consumer).
 */
@Component
public class PixNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(PixNotificationConsumer.class);

    @KafkaListener(topics = "pix.transaction.completed", groupId = "notification-service")
    public void onTransactionCompleted(PixTransactionEvent event) {
        log.info(
                "[NOTIFICAÇÃO] Pix de R$ {} confirmado. endToEndId={}, origem={}, destino={} — " +
                        "notificação simulada enviada ao pagador e ao recebedor.",
                event.amount(), event.endToEndId(), event.sourceAccountId(), event.targetAccountId()
        );
    }

    @KafkaListener(topics = "pix.transaction.failed", groupId = "notification-service")
    public void onTransactionFailed(PixTransactionEvent event) {
        log.warn(
                "[NOTIFICAÇÃO] Pix de R$ {} FALHOU. endToEndId={}, origem={} — " +
                        "notificação de falha simulada enviada ao pagador.",
                event.amount(), event.endToEndId(), event.sourceAccountId()
        );
    }
}
