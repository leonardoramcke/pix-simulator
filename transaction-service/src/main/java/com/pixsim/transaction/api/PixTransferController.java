package com.pixsim.transaction.api;

import com.pixsim.transaction.application.PixTransferService;
import com.pixsim.transaction.domain.PixTransaction;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pix")
public class PixTransferController {

    private final PixTransferService transferService;

    public PixTransferController(PixTransferService transferService) {
        this.transferService = transferService;
    }

    public record TransferRequest(
            @NotBlank String endToEndId,
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount
    ) {}

    public record TransferResponse(UUID transactionId, String status) {}

    @PostMapping("/transfer")
    @RateLimiter(name = "pixTransfer")               // limita throughput por instância
    @CircuitBreaker(name = "pixTransfer", fallbackMethod = "transferFallback")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        PixTransaction tx = transferService.transfer(
                request.endToEndId(), request.sourceAccountId(),
                request.targetAccountId(), request.amount());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TransferResponse(tx.getId(), tx.getStatus().name()));
    }

    // Fallback do Circuit Breaker: acionado quando o serviço está em
    // estado OPEN (muitas falhas recentes) — responde rápido em vez de
    // deixar o cliente esperar timeout, e sinaliza retry mais tarde.
    private ResponseEntity<TransferResponse> transferFallback(TransferRequest request, Throwable t) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new TransferResponse(null, "TEMPORARILY_UNAVAILABLE"));
    }
}
