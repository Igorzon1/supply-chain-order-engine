# OpenERP Core — notas da implementação V1

**Estado em 27/09/2026:** código da primeira entrega criado; execução e testes ainda não confirmados neste ambiente. Este documento descreve o que existe no repositório, as escolhas do protótipo e seus limites. O [escopo da entrega](ESCOPO_V1.md) explica o objetivo; os [requisitos](REQUISITOS.md) descrevem o produto mais amplo; os [casos de uso V1](CASOS_DE_USO_V1.md) detalham a interação desejada; o [modelo de domínio](MODELO_DOMINIO.md) mostra entidades e relações; o [README](../README.md) traz os comandos e exemplos de uso.

## Objetivo da V1

Comprovar o fluxo **compra → recebimento → estoque → pedido → reserva**. A regra que orienta o recorte é não permitir que pedidos simultâneos reservem mais unidades do que o estoque disponível. Esse fluxo foi escolhido antes de pagamento e expedição porque ambos dependem de um saldo confiável.

## O que foi implementado

| Área | Comportamento atual |
| --- | --- |
| Cadastros | Protótipo: criação e consulta por ID de produto, cliente e fornecedor; SKU e códigos externos são informados pelo usuário e únicos. Requisito atualizado: SKU automático; código externo opcional. |
| Compras | Criação de ordem com fornecedor, autor interno, itens, quantidades e custos; recebimento integral e único. |
| Estoque | Saldos físico, reservado e disponível por SKU; custo médio ponderado; consulta do livro-razão por SKU. |
| Vendas | Criação de pedido com cliente, itens e `Idempotency-Key`; reserva de todos os itens na mesma transação. |
| Infraestrutura | Migração Flyway, PostgreSQL, Docker Compose, autenticação Basic para ambiente local e testes de integração escritos com Testcontainers. |

Uma compra ou venda sem contraparte cadastrada é rejeitada. A API usa um usuário interno autenticado para registrar o autor da compra e do pedido. Na V1, o cliente é vinculado ao pedido, mas não acessa a API com conta própria: o funcionário cria o pedido em seu nome.

## Estrutura do código

- [`catalog`](../src/main/java/com/openerp/catalog): entidades e repositórios Spring Data JPA para os cadastros.
- [`procurement`](../src/main/java/com/openerp/procurement): ordem de compra e recebimento.
- [`inventory`](../src/main/java/com/openerp/inventory): leitura dos saldos e das movimentações.
- [`sales`](../src/main/java/com/openerp/sales): criação idempotente e reserva do pedido.
- [`common`](../src/main/java/com/openerp/common): autenticação e respostas de erro.
- [Migração V1](../src/main/resources/db/migration/V1__initial.sql): tabelas, chaves, restrições e gatilho append-only.

Esta separação por pacote é o início do monólito modular. **As regras transacionais ainda estão nos controllers.** Foi uma forma curta de construir o primeiro fluxo, mas o próximo refino arquitetural deve extrair serviços de aplicação e deixar os controllers com a responsabilidade de receber e devolver HTTP. A V1 ainda não tem um módulo `finance`, pois esse fluxo está fora do recorte.

## Decisões de negócio adotadas para a V1

### Recebimento e custo

- Cada ordem de compra é recebida por completo, uma vez. Repetir a confirmação retorna a compra já recebida sem lançar outra entrada.
- Quantidades são inteiras e positivas. Cada produto aparece no máximo uma vez por compra.
- Custo unitário não pode ser negativo. O custo é armazenado com quatro casas decimais; a entrada arredonda com `HALF_UP`.
- Novo custo médio = `(saldo físico anterior × custo médio anterior + quantidade recebida × custo unitário) ÷ novo saldo físico`, com quatro casas decimais e `HALF_UP`. Quando o saldo anterior é zero, o resultado é o custo da entrada.
- Frete, impostos, descontos, avarias e recebimento parcial ainda não entram no cálculo.

### Estoque e reserva

- `disponível = físico − reservado`; o banco impede saldos negativos e reserva maior que o físico.
- O recebimento aumenta o físico; a reserva aumenta apenas o reservado. O físico só será reduzido no futuro fluxo de expedição.
- Cada mudança feita por esses fluxos grava uma movimentação com tipo, deltas, produto, operação de origem, autor e data.
- O livro-razão aceita apenas `INSERT`: um gatilho do PostgreSQL rejeita `UPDATE` e `DELETE`. Uma correção futura deverá ser outra movimentação, quando o respectivo caso de uso for implementado.

### Pedido e idempotência

- A criação exige cliente cadastrado, itens positivos e não repetidos e `Idempotency-Key` não vazia de até 120 caracteres.
- A chave é única em toda a tabela de pedidos e permanece armazenada sem prazo de expiração na V1.
- O conteúdo comparado inclui autor, cliente e lista de produtos/quantidades ordenada por produto. Repetir chave e conteúdo devolve o mesmo pedido; repetir a chave com conteúdo diferente devolve conflito.
- Um lock transacional por chave serializa tentativas simultâneas da mesma operação. A restrição de unicidade no banco também protege a chave.
- O pedido é inserido como `CRIADO` e passa a `RESERVADO` na mesma transação. Para o cliente da API, um pedido criado com sucesso aparece `RESERVADO`; falha de reserva desfaz a criação inteira.
- Produtos são bloqueados em ordem crescente de ID com `SELECT ... FOR UPDATE`. Se qualquer um não tiver saldo, nenhum item do pedido fica reservado.

## Por que JPA e SQL explícito coexistem

JPA reduz código repetitivo nos cadastros. Para o fluxo crítico de estoque, `JdbcTemplate` deixa visíveis a consulta com bloqueio, a ordem de aquisição dos locks e as gravações de saldo e ledger. Ambos usam as transações gerenciadas pelo Spring. Esta é uma decisão do protótipo, a ser reavaliada depois de executar e medir os testes; não é uma afirmação de que SQL explícito seja sempre superior.

## Persistência e proteção dos dados

O PostgreSQL guarda `supplier`, `customer`, `product`, `purchase_order`, `purchase_line`, `sales_order`, `sales_line`, `inventory_balance` e `inventory_ledger`. Flyway cria o esquema. `inventory_balance` é uma projeção mutável para leitura e bloqueio; `inventory_ledger` é o histórico imutável das operações implementadas.

**Limite atual:** o gatilho protege o livro-razão contra edição ou exclusão, mas não impede que alguém com acesso direto ao banco atualize `inventory_balance` sem registrar movimentação. A garantia de que saldo e histórico mudam juntos depende dos fluxos transacionais da aplicação e das permissões concedidas ao usuário do banco. Uma política de acesso mais restritiva ou proteção adicional no banco deve ser considerada antes de uso real.

## API do protótipo atual

| Método | Rota | Finalidade |
| --- | --- | --- |
| POST / GET por ID | `/api/products` | Criar produto / consultar `/api/products/{id}`. |
| POST / GET por ID | `/api/suppliers` | Criar fornecedor / consultar `/api/suppliers/{id}`. |
| POST / GET por ID | `/api/customers` | Criar cliente / consultar `/api/customers/{id}`. |
| POST / GET por ID | `/api/purchases` | Criar compra / consultar `/api/purchases/{id}`. |
| POST | `/api/purchases/{id}/receive` | Confirmar recebimento completo. |
| GET | `/api/inventory/{sku}` | Consultar saldos e custo médio. |
| GET | `/api/inventory/{sku}/movements` | Consultar histórico de estoque. |
| POST / GET por ID | `/api/orders` | Criar e reservar pedido / consultar `/api/orders/{id}`. |

O protótipo ainda não tem rota para pesquisar produtos por nome ou SKU; linhas de compra recebem `productId`. A rota de estoque usa SKU, e pedidos são consultados pelo ID do pedido. Consultas financeiras não estão implementadas.

Não há interface gráfica nesta versão: o README demonstra chamadas diretas à API. Os casos de uso especificam que uma futura interface deverá oferecer busca e seleção e não pedir ao funcionário para digitar IDs, SKUs ou chaves de idempotência. A consulta de pedido atual exige informar seu ID; a busca por cliente/período/estado ainda precisa de endpoint.

As rotas de criação e consulta exigem HTTP Basic. O Compose lê `DB_PASSWORD` e `APP_STAFF_PASSWORD` de um `.env` local ignorado pelo Git; não há senhas padrão versionadas. Não há autenticação do cliente final, perfis de funcionários, aprovação por supervisor nem HTTPS configurado na aplicação.

## Testes e estado da validação

A classe [`FirstScopeIntegrationTest`](../src/test/java/com/openerp/FirstScopeIntegrationTest.java) contém cinco cenários: recebimento único e custo médio; repetição e conflito da chave de idempotência; duas tentativas simultâneas com a mesma chave; disputa simultânea pela última unidade; e rollback de reserva de múltiplos itens quando um está indisponível. Os testes usam PostgreSQL temporário via Testcontainers. Alguns fixtures colocam saldo inicial diretamente na tabela para isolar a regra de reserva; eles não demonstram, por si só, a integridade completa entre saldo e ledger.

**Verificações feitas em 27/09/2026 neste ambiente:**

- `docker build --quiet -t supply-chain-order-engine:validation .` concluiu com sucesso. O Dockerfile compilou e empacotou a aplicação com Maven 3.9 e JDK 21, dentro da imagem de build.
- `mvn -B -ntp test` concluiu com sucesso dentro de `maven:3.9-eclipse-temurin-21`, com o socket Docker montado e `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`. Resultado: **5 testes, 0 falhas, 0 erros, 0 ignorados**.
- O Testcontainers iniciou PostgreSQL 16.15 e o Flyway validou e aplicou a migração V1.
- `docker compose up --build -d --wait` iniciou PostgreSQL e API em um projeto Compose temporário e isolado; ambos ficaram saudáveis.
- Um smoke test REST percorreu cadastro de fornecedor, cliente e produto; criação e recebimento repetido de uma compra; consulta de saldo; e criação de pedido. O pedido terminou em `RESERVADO`; repetir a mesma chave de idempotência retornou o mesmo pedido. O saldo final foi físico `10`, reservado `2` e disponível `8`; o ledger continha uma entrada e uma reserva.
- O projeto Compose temporário, seus contêineres e o volume de teste foram removidos ao final. As senhas aleatórias existiram apenas nos processos e contêineres temporários; nenhum `.env` foi criado.

**Como reproduzir:** no host desta sessão, Java é 8 e Maven não está no `PATH`, mas a suíte foi executada com JDK 21 e Maven pelo comando Docker documentado no README. Para subir a API com o Compose normal, copie `.env.example` para `.env` e preencha `DB_PASSWORD` e `APP_STAFF_PASSWORD` com valores locais. A validação não incluiu teste de carga prolongado nem as funcionalidades que estão fora da V1.

## Fora da V1 e próximos cuidados

- Pagamento, título a receber, cancelamento com motivo, análise do supervisor, estorno, expedição, entrega e emissão simulada de nota continuam nos [requisitos gerais](REQUISITOS.md).
- Recebimento parcial, avaria, frete, impostos, descontos e unidades fracionárias não estão implementados.
- Redis foi excluído da V1: o PostgreSQL decide a reserva de forma transacional. Seu uso futuro depende de uma necessidade medida.
- A máquina de estados completa ainda não existe: a V1 implementa apenas a passagem interna `CRIADO → RESERVADO`, sem endpoint de transição ou teste de rejeição de outras transições.
- Antes de ampliar o sistema, executar a suíte de integração, corrigir falhas observadas, testar a API pela rede e registrar as decisões restantes em ADRs específicos.
