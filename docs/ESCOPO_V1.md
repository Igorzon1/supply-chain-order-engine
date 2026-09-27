# Escopo da primeira entrega — OpenERP Core

**Estado:** escopo inicial escolhido e codificado como protótipo; a compilação, a execução e os testes ainda não foram validados neste ambiente. Consulte as [notas da implementação V1](IMPLEMENTACAO_V1.md) para distinguir o código existente dos critérios ainda não comprovados.

## Objetivo

Demonstrar, de ponta a ponta, que uma compra recebida gera estoque rastreável e que pedidos de clientes reservam esse estoque sem comprometer a mesma unidade duas vezes, mesmo quando chegam simultaneamente.

## Por que começar por este recorte

O diferencial técnico central do projeto é a consistência do estoque sob concorrência. Se compras, movimentações e reservas não estiverem corretas, pagamento, expedição e faturamento serão construídos sobre saldos pouco confiáveis. Este recorte permite validar primeiro essa base com um fluxo real pela API e testes contra PostgreSQL.

Ele também produz uma entrega demonstrável: é possível apresentar uma entrada de mercadoria, consultar o saldo, criar pedidos e mostrar que apenas a quantidade disponível é reservada. Isso dá evidência concreta para o portfólio sem exigir que todos os módulos estejam prontos ao mesmo tempo.

O recorte limita regras excepcionais que ainda precisam de definição, como estorno, devolução e recebimento parcial. Essas regras continuam no plano do produto; ficam para entregas posteriores para que a primeira implementação tenha uma conclusão verificável.

## Incluído na primeira entrega

1. Cadastro e consulta mínimos de produto, fornecedor e cliente, com identificadores únicos.
2. Registro de ordem de compra por um usuário interno identificado, com fornecedor, itens, quantidades e custos unitários.
3. Recebimento **completo** de uma ordem de compra, uma única vez, com entrada no estoque e atualização do custo médio conforme política simples a detalhar.
4. Consulta de saldo físico, reservado e disponível por SKU.
5. Livro-razão append-only de entradas e reservas, incluindo referência à operação e ao responsável ou origem.
6. Criação de pedido por API com cliente e origem identificados, além de chave de idempotência.
7. Reserva atômica de todos os itens do pedido, com rejeição integral se faltar saldo.
8. Transições `CRIADO → RESERVADO` e rejeição de transições inválidas nessa etapa.
9. Testes automatizados das regras, da idempotência e de reservas simultâneas contra PostgreSQL real com Testcontainers.
10. Execução local documentada com Docker Compose e migrações Flyway.

## Fora desta entrega, previstos para as próximas

- Confirmação de pagamento, título a receber e quitação.
- Cancelamento, liberação de reserva, análise do supervisor, estorno e devolução.
- Expedição, entrega, emissão simulada de nota e eventos de domínio associados.
- Recebimento parcial de compras, avarias e custos adicionais como frete e impostos.
- Cache e Redis. A reserva de estoque será protegida por transações no PostgreSQL.

## Critérios de conclusão

A primeira entrega estará concluída quando:

- Um usuário conseguir cadastrar os dados mínimos, registrar e receber uma compra, consultar o estoque, criar um pedido e reservá-lo pela API seguindo instruções documentadas.
- Cada entrada e reserva puder ser explicada por registros imutáveis vinculados à operação de origem.
- Repetir a confirmação de recebimento não gerar nova entrada; repetir a criação de pedido com a mesma chave e conteúdo devolver o resultado original, enquanto reutilizar a chave com conteúdo diferente resulta em rejeição.
- Uma reserva de múltiplos itens falhar por completo se qualquer item não tiver saldo suficiente.
- Em teste de concorrência, a soma das reservas aprovadas nunca superar o saldo disponível e os saldos permanecerem válidos.
- Os testes de integração executarem contra PostgreSQL em contêiner, e o ambiente local puder ser iniciado pelo procedimento documentado.

## Decisões adotadas no protótipo

1. O comportamento desejado é gerar SKU único e estável no cadastro, sem depender do nome; cliente e fornecedor recebem ID interno, e código externo passa a ser opcional para integração/importação. O protótipo atual ainda exige SKU/código externo informados manualmente. Um usuário interno de demonstração é identificado por HTTP Basic; o cliente é vinculado ao pedido, mas não se autentica na API.
2. O custo médio usa saldo físico e custo anterior mais a nova entrada; o resultado tem quatro casas decimais com `HALF_UP`. Estoque anterior zero resulta no custo da entrada.
3. A chave de idempotência é única globalmente, compara autor, cliente e itens e não expira na V1.
4. A reserva altera o saldo reservado e cria uma movimentação append-only na mesma transação. O banco impede edição ou exclusão do ledger; acesso direto ao saldo ainda exige controle de permissões.

Os detalhes e limites dessas decisões estão nas [notas da implementação V1](IMPLEMENTACAO_V1.md). Os fluxos e critérios de aceite estão nos [casos de uso V1](CASOS_DE_USO_V1.md), e as entidades e relações no [modelo de domínio](MODELO_DOMINIO.md). O documento de [requisitos](REQUISITOS.md) continua descrevendo o produto mais amplo; esta página delimita somente a primeira entrega.
