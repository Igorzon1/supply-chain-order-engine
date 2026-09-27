# OpenERP Core — Order-to-Cash & Supply Chain Engine

Esta é a ideia do projeto: desenvolver um backend modular para gestão da cadeia de suprimentos e do ciclo de vendas, com foco nas regras de negócio críticas e na resiliência transacional de um sistema corporativo. O projeto prioriza o funcionamento do motor de negócio, sem foco em telas decorativas.

## Fluxo previsto

1. **Compras:** registrar ordens de compra de fornecedores e calcular o custo médio ponderado por SKU.
2. **Estoque:** distinguir saldo físico de saldo reservado e manter um livro-razão imutável para entradas, saídas, avarias e reservas.
3. **Vendas:** receber pedidos por API REST, aceitar `Idempotency-Key` e reservar estoque de forma atômica sob concorrência.
4. **Ciclo do pedido:** controlar transições válidas entre `CRIADO`, `RESERVADO`, `PAGO`, `EXPEDIDO`, `ENTREGUE` e `CANCELADO`.
5. **Financeiro e eventos:** gerar títulos a receber e publicar eventos internos para simular expedição e emissão de notas.

## Arquitetura proposta

- Java 21+ e Spring Boot 3+, com Virtual Threads para operações bloqueantes de I/O.
- Monólito modular, orientado por princípios de DDD e Clean Architecture, com módulos `procurement`, `inventory`, `sales` e `finance` comunicando-se por eventos de domínio internos.
- Spring Data JPA e Hibernate com PostgreSQL; migrações de banco pelo Flyway.
- Sem Redis na versão inicial: a reserva de estoque será garantida por transações e bloqueios no PostgreSQL; cache só será considerado se medições demonstrarem necessidade.
- Testes com JUnit 5, Mockito e Testcontainers para PostgreSQL.
- Inicialização do ambiente por `docker-compose.yml`.

## Pontos técnicos a demonstrar

- Bloqueio pessimista no estoque durante vendas simultâneas, impedindo reservas acima do saldo disponível.
- Idempotência na criação de pedidos e no processamento de pagamentos.
- Auditoria append-only das movimentações de estoque, sem alteração de saldo sem histórico correspondente.
- Registros de decisões de arquitetura em `/docs/adr`, cobrindo locks, cache e isolamento dos módulos.

## Objetivo de portfólio e autoria

Este projeto será desenvolvido de ponta a ponta para compor meu portfólio e demonstrar a recrutadores minha capacidade de conceber, organizar, implementar e explicar um sistema backend com problemas reais de negócio.

A concepção do produto, a definição do escopo, as prioridades e as decisões de engenharia serão conduzidas por mim. A IA poderá auxiliar na pesquisa, no planejamento, na implementação, na revisão e nos testes, mas as decisões finais e a validação do resultado serão minhas. O portfólio deve apresentar essa colaboração com transparência, sem atribuir a mim trabalho que não fiz.

Para tornar minha participação verificável, pretendo manter um histórico de evolução do projeto: requisitos e critérios de aceite, decisões arquiteturais com alternativas consideradas, marcos de implementação, testes de concorrência e idempotência, problemas encontrados e justificativas para as soluções escolhidas. A apresentação final deve mostrar o sistema funcionando e explicar por que cada decisão importante foi tomada.

> Este arquivo registra somente a proposta do projeto; a implementação ainda não foi iniciada.
