package com.pixsim.notification.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Espelha a estrutura de PixTransaction publicada pelo transaction-service
 * no Kafka. Em uma arquitetura de microsserviços real, isso normalmente
 * viria de um contrato compartilhado (schema registry, OpenAPI, ou um
 * módulo Java comum) — aqui, replicado deliberadamente para manter os
 * serviços independentes e deployáveis isoladamente (um dos princípios
 * centrais de microsserviços: nenhum serviço deve depender do código
 * interno de outro, só do contrato do evento).
 */
public record PixTransactionEvent(
        UUID id,
        String endToEndId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String status
) {
}
