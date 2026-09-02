# Pix Simulator — Simulador de Transferência Instantânea de Alta Performance

Simulador de um sistema de pagamentos instantâneos (inspirado no Pix, do
Banco Central do Brasil), construído como projeto de portfólio para
demonstrar competências de engenharia de backend voltadas ao setor
financeiro: **transações ACID, controle de concorrência, resiliência,
observabilidade e arquitetura de microsserviços**.

> Este não é um projeto de "CRUD com Kafka por cima". Cada decisão técnica
> abaixo existe para resolver um problema real de sistemas de pagamento:
> saldo nunca pode ficar inconsistente, uma transferência nunca pode ser
> duplicada, e o sistema precisa continuar respondendo mesmo sob falhas
> parciais.

## Arquitetura

```
                        ┌─────────────────┐
                        │   API Gateway    │   (ponto de entrada — não
                        │   (não incluso)  │    implementado neste MVP)
                        └────────┬─────────┘
                                 │
                                 ▼
                 ┌───────────────────────────────┐
                 │     transaction-service        │   porta 8080
                 │  (núcleo financeiro — ACID)    │
                 └───┬───────────────────────┬────┘
                     │                       │
                     │ lê/escreve            │ publica eventos
                     ▼                       ▼
              ┌─────────────┐         ┌─────────────┐
              │  PostgreSQL  │         │    Kafka     │
              │  (accounts,  │         │ pix.transaction.
              │ transactions)│         │  completed/failed
              └──────┬───────┘         └──────┬──────┘
                     │                        │
                     │ mesma tabela           │ consome
                     │ 'accounts'             ▼
                     │                 ┌─────────────────┐
                     ▼                 │ notification-    │  porta 8082
              ┌─────────────┐          │ service           │
              │  account-    │          └─────────────────┘
              │  service     │  porta 8081
              └─────────────┘

              ┌─────────────┐         ┌─────────────┐
              │ Prometheus   │◄────────│  Grafana     │
              │ (coleta)     │  query  │ (dashboard)  │
              └─────────────┘         └─────────────┘
```

### Microsserviços

| Serviço | Porta | Responsabilidade |
|---|---|---|
| **transaction-service** | 8080 | Orquestra transferências Pix: débito/crédito atômico, idempotência, controle de concorrência, publicação de eventos |
| **account-service** | 8081 | Criação, consulta e exclusão de contas via API REST |
| **notification-service** | 8082 | Consome eventos Kafka e simula envio de notificação (push/SMS/e-mail) ao usuário |
| **frontend** | — | Interface web estática (sem build) para demonstração visual do fluxo completo |

### Infraestrutura (Docker Compose)

- **PostgreSQL 16** — armazenamento transacional
- **Apache Kafka 3.7** (modo KRaft, sem Zookeeper) — mensageria assíncrona entre serviços
- **Redis 7** — disponível para idempotência/rate limiting distribuído (reservado para evolução futura)
- **Kafka UI** — inspeção visual de tópicos e mensagens (`localhost:8085`)
- **Prometheus** — coleta de métricas (`localhost:9090`)
- **Grafana** — dashboard de observabilidade (`localhost:3000`)

## Por que essas escolhas técnicas

| Decisão | Motivo |
|---|---|
| **Java 21 + Spring Boot 3** | Virtual threads (Project Loom) para alta concorrência; `@Transactional` declarativo maduro para lógica ACID |
| **PostgreSQL** | ACID real, lock otimista via `@Version`, isolamento `REPEATABLE_READ` para detectar conflitos de escrita concorrente |
| **Apache Kafka** | Entrega ordenada por partição (chave = conta), histórico replay-ável — rastreabilidade é requisito real de sistemas de pagamento |
| **Resilience4j** | Circuit Breaker, Rate Limiter e Retry declarativos, sem acoplar lógica de resiliência ao código de negócio |
| **Flyway** | Versionamento de schema auditável — essencial em ambiente regulado |

## Decisões de concorrência (o coração do projeto)

Transferir dinheiro entre contas sob alta concorrência é o problema mais
difícil deste projeto. As decisões abaixo foram validadas com testes reais
de carga (50 threads simultâneas na mesma conta — ver seção de testes).

### 1. Lock otimista como estratégia primária

`Account` usa `@Version` (lock otimista) em vez de `SELECT FOR UPDATE`
(lock pessimista) como caminho padrão. Lock pessimista serializa todo
acesso à mesma linha — em uma conta de alto volume (ex: um Pix recebido
por uma loja popular), isso vira gargalo. Lock otimista permite tentativas
concorrentes e só rejeita quem realmente colidiu.

### 2. Duas camadas de detecção de conflito — e por que isso importa

Sob isolamento `REPEATABLE_READ`, o **próprio PostgreSQL** detecta
conflitos de escrita concorrente e lança um erro de serialização
(`SQLSTATE 40001`), traduzido pelo Spring para `CannotAcquireLockException`
— **antes mesmo** do `@Version` da aplicação entrar em ação. Isso gerou um
bug real durante o desenvolvimento: o retry estava configurado para
escutar apenas `OptimisticLockingFailureException`, então metade das
falhas de concorrência não eram tratadas. A correção foi escutar o tipo-pai
comum, `ConcurrencyFailureException`, cobrindo ambas as camadas.

### 3. Ordenação determinística de locks

Ao buscar as contas de origem e destino, elas são sempre bloqueadas na
ordem crescente do UUID — nunca "pagador, depois recebedor". Isso evita o
deadlock clássico: transação A trava conta 1 e espera a 2, enquanto uma
transferência inversa (transação B) trava a 2 e espera a 1.

### 4. Idempotência via `endToEndId`

Toda transferência carrega uma chave de idempotência (`endToEndId`, nome
real do campo usado pelo Pix/BACEN). Reenviar a mesma requisição — cenário
comum quando um cliente HTTP sofre timeout e tenta de novo — retorna o
resultado já processado, sem duplicar o débito.

## Resiliência

- **Circuit Breaker**: abre após 50% de falhas em uma janela de 50
  chamadas; passa por estado `half-open` antes de fechar de novo.
  Configurado para **ignorar exceções de negócio** (saldo insuficiente,
  dados inválidos) na contagem de falhas — o sistema está saudável quando
  rejeita um pedido inválido, não é uma falha de infraestrutura.
- **Retry com backoff exponencial**: até 15 tentativas para conflitos de
  concorrência, nunca para regras de negócio.
- **Rate Limiter**: 200 requisições/segundo por instância.

## Observabilidade

Dashboard Grafana pré-provisionado (carrega automaticamente, sem
configuração manual) com:
- Taxa de requisições e latência p50/p95/p99
- Taxa de sucesso vs. erro
- Estado do circuit breaker em tempo real
- Pool de conexões do banco (HikariCP)
- Volume de retries por resultado (sucesso/falha, com/sem retry)

## Frontend web

Interface web (`frontend/index.html` — HTML/CSS/JS puro, sem build,
sem npm) para demonstrar visualmente o fluxo completo, além de servir
como cliente de teste manual mais rápido que `curl`:

- **Tipos de chave Pix reais**: E-mail, CPF (com máscara automática),
  Celular e Aleatória (gera um UUID de verdade, no mesmo formato usado
  pelo backend) — o tipo de cada conta é inferido visualmente pelo
  formato da chave, como o Pix real classifica chaves cadastradas.
- **Comprovante de transferência**: modal estilo recibo bancário, com
  valor em destaque, dados completos da transação e linha tracejada —
  aparece automaticamente após cada transferência bem-sucedida.
- **Exclusão de chave Pix**: com confirmação em dois cliques. Se a
  conta já tiver participado de alguma transferência, a exclusão é
  recusada (a chave estrangeira do banco protege o histórico), e o
  erro do Postgres é traduzido numa mensagem amigável em vez de
  vazar detalhes internos.
- **Copiar chave com um clique**, feed de atividade da sessão em tempo
  real, e indicador de saúde dos serviços (`serviços conectados` /
  `sem conexão`).

## Testes automatizados

7 testes cobrindo desde regras de negócio isoladas até concorrência real:

**Unitários** (`PixTransferServiceTest`) — regras de negócio com mocks, sem infraestrutura:
- Transferência com sucesso debita/credita corretamente
- Saldo insuficiente não altera nenhum saldo
- Idempotência para `endToEndId` repetido
- Valor zero/negativo rejeitado
- Conta inexistente tratada

**Integração** (`PixTransferConcurrencyIT`) — Postgres real via Docker Compose:
- **50 transferências simultâneas** na mesma conta: saldo final exato, sem perda nem duplicação
- **8 requisições concorrentes com o mesmo `endToEndId`**: só uma transferência de fato acontece

> **Nota de ambiente**: os testes de integração usam o Postgres já
> provisionado pelo `docker-compose.yml` em vez de subir um container
> efêmero via Testcontainers. Essa foi uma decisão pragmática tomada após
> incompatibilidade persistente entre o cliente Docker do Testcontainers e
> uma instalação específica do Docker Desktop no Windows. Trade-off
> assumido: os testes de integração **limpam as tabelas** antes de rodar,
> então rodar a suíte de testes localmente apaga contas de desenvolvimento
> manualmente inseridas — é necessário reaplicar o seed
> (`V2__seed_test_accounts.sql` ou reinserir manualmente) após rodar os
> testes. Em CI (ambiente Linux), a abordagem correta seria reverter para
> Testcontainers.

## Como rodar localmente

### Pré-requisitos
- Docker Desktop (é a única dependência real — os microsserviços rodam
  em containers, não é necessário ter Java/Maven instalados)

### Modo rápido — um único comando sobe tudo

```bash
docker compose up -d --build
```

Isso sobe a infraestrutura (Postgres, Kafka, Redis, Prometheus, Grafana)
**e** os 3 microsserviços (`transaction-service`, `account-service`,
`notification-service`), cada um com seu próprio `Dockerfile` multi-stage
(build com JDK completo, runtime só com JRE — imagem final enxuta).

Na primeira vez, o build das imagens Java leva alguns minutos (baixa
dependências Maven dentro do container). Builds seguintes são rápidos,
graças ao cache de camadas do Docker.

```bash
docker compose ps   # confirme que todos os containers estão Up/healthy
```

Depois disso, abra `frontend/index.html` diretamente no navegador — a
interface web já se conecta em `localhost:8080` e `localhost:8081`
normalmente, pois as portas dos serviços são publicadas no host mesmo
rodando dentro do Docker.

### Modo desenvolvimento — rodar um serviço fora do Docker

Útil ao editar código e querer reiniciar só um serviço rapidamente, sem
rebuildar a imagem inteira. Nesse caso, é necessário ter Java 21
instalado (Maven não — os projetos usam Maven Wrapper, `mvnw`).

```bash
# sobe só a infraestrutura, sem os microsserviços
docker compose up -d postgres redis kafka kafka-ui prometheus grafana

# roda o serviço que você está editando direto na máquina
cd transaction-service
./mvnw spring-boot:run
```

O `application.yml` de cada serviço já aponta para `localhost`, então
funciona automaticamente nesse modo sem precisar mudar nada.

### Testar uma transferência

```bash
curl -X POST http://localhost:8080/api/v1/pix/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "endToEndId": "E12345678202601011200001",
    "sourceAccountId": "11111111-1111-1111-1111-111111111111",
    "targetAccountId": "22222222-2222-2222-2222-222222222222",
    "amount": 100.00
  }'
```

A notificação simulada deve aparecer no log do `notification-service`
poucos milissegundos depois (`docker compose logs -f notification-service`).

### Explorar a observabilidade

- Grafana: `http://localhost:3000` (login `admin`/`admin`) → dashboard "Pix Simulator - Transaction Service"
- Prometheus: `http://localhost:9090/targets`
- Kafka UI: `http://localhost:8085`

### Rodar os testes

Os testes rodam fora do Docker (usam Maven Wrapper diretamente), contra a
infraestrutura já subida via `docker compose`:

```bash
cd transaction-service
./mvnw test                                    # só unitários
./mvnw test -Dtest=PixTransferConcurrencyIT    # integração (requer Docker Compose no ar)
```

## Simplificações e trade-offs conscientes

Todo projeto real envolve decisões de escopo. Documentar essas decisões
explicitamente é, na minha visão, tão importante quanto o código em si:

- **Banco de dados compartilhado** entre `transaction-service` e
  `account-service`: ambos leem/escrevem na mesma tabela `accounts`. É um
  padrão real de transição de monolito para microsserviços, mas não é o
  estado final desejado — o próximo passo seria dar ao `account-service`
  seu próprio schema, e o `transaction-service` passaria a consultar saldo
  via chamada de API em vez de acesso direto ao banco.
- **Padrão Outbox simplificado**: o `transaction-service` publica eventos
  no Kafka dentro do mesmo método transacional que persiste no Postgres,
  sem uma tabela de outbox intermediária. Em produção, isso arrisca
  inconsistência entre banco e Kafka se um dos dois falhar isoladamente
  (dual write). A evolução correta é uma tabela `outbox_events` lida por
  um processo CDC (ex: Debezium).
- **CORS liberado para qualquer origem** (`allowedOriginPatterns("*")`) em
  `transaction-service` e `account-service`, para o frontend de
  demonstração acessar a API sem fricção. Em produção, isso deveria ser
  restrito a domínios específicos conhecidos — wildcard é aceitável aqui
  porque o ambiente é 100% local, sem exposição à internet e sem
  autenticação baseada em cookie/sessão que pudesse ser explorada por essa
  configuração permissiva.
- **Sem API Gateway**: cada serviço é acessado diretamente por porta.
  Um ambiente de produção teria um gateway único como ponto de entrada,
  cuidando de autenticação, roteamento e rate limiting centralizados.
- **Testes de integração contra banco de desenvolvimento**, não
  Testcontainers (ver seção de testes acima) — decisão pragmática
  documentada, não ideal.

## Roadmap (não implementado neste MVP)

- [ ] Padrão Outbox real com Debezium/CDC
- [ ] API Gateway (Spring Cloud Gateway) como ponto de entrada único
- [ ] Fraud Service consumindo os mesmos eventos Kafka de forma independente
- [ ] Testes de carga automatizados (k6/Gatling)
- [ ] CI/CD com GitHub Actions (build + testes a cada push)
- [ ] Extração de `account-service` para schema/banco próprio
- [ ] Extrato/histórico de transações por conta (endpoint dedicado no `transaction-service`)
