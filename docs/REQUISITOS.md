# Requisitos — OpenERP Core

**Estado:** proposta inicial para revisão do autor. Este documento traduz a ideia do projeto em comportamentos verificáveis. Os pontos em aberto ao final exigem decisão antes da implementação correspondente.

## Escopo e termos

O sistema cobre o fluxo de compra de produtos, entrada em estoque, venda, reserva, pagamento, expedição, entrega e geração de títulos a receber. Nesta proposta, **SKU** identifica um produto; **saldo físico** representa unidades presentes; **saldo reservado** representa unidades comprometidas com pedidos; **saldo disponível** é `físico − reservado`. A forma de representar esses saldos no banco será decidida na modelagem, preservando o livro-razão de movimentações.

## Requisitos funcionais

| ID | Requisito | Critério de aceite inicial |
| --- | --- | --- |
| RF-01 | Cadastrar e consultar produtos com SKU gerado pelo sistema. | O usuário informa o nome; o sistema gera um SKU único e estável, independente do nome, que pode ser consultado e usado como referência nas operações. |
| RF-02 | Registrar ordens de compra com fornecedor identificado, itens existentes, quantidades e custos unitários informados com base no fornecedor. | Uma ordem sem fornecedor ou com produto não cadastrado é rejeitada; uma ordem válida pode ser consultada com fornecedor, itens e valores. |
| RF-03 | Confirmar o recebimento completo de uma compra e lançar as entradas de estoque. | As quantidades conferidas correspondem às quantidades ordenadas; a confirmação aumenta o saldo físico uma única vez e cria movimentações correspondentes. |
| RF-04 | Atualizar o custo médio ponderado do SKU após entrada de compra. | O custo resulta de `(saldo físico anterior × custo médio anterior + quantidade recebida × custo unitário) ÷ novo saldo físico`, com quatro casas decimais e arredondamento `HALF_UP`; com saldo anterior zero, usa o custo da entrada. |
| RF-05 | Consultar saldo físico, reservado e disponível por SKU. | Os três valores refletem as movimentações confirmadas e obedecem a `disponível = físico − reservado`. |
| RF-06 | Registrar avaria de produtos. | A quantidade física cai e a movimentação de avaria fica registrada; não se aceita avaria que torne o saldo físico incompatível com reservas existentes. |
| RF-07 | Criar pedidos de venda via API REST com cliente identificado, itens e quantidades. | Uma solicitação sem cliente é rejeitada; uma solicitação válida cria um único pedido associado ao cliente e devolve seu identificador e estado. |
| RF-08 | Aceitar uma chave de idempotência gerada pelo cliente da API para cada intenção de criar pedido. | A interface gera e preserva a chave em reenvios da mesma tentativa; mesma chave e conteúdo devolve o resultado original; mesma chave com conteúdo diferente é rejeitada; nova chave permite um novo pedido, mesmo com conteúdo igual. |
| RF-09 | Reservar estoque para um pedido de forma atômica. | Todos os itens são reservados juntos ou nenhum é; falta de saldo rejeita a reserva sem alterar saldos. |
| RF-10 | Confirmar pagamento de forma idempotente e quitar o título a receber. | Repetir a mesma confirmação não duplica a baixa financeira nem avança o estado mais de uma vez; o título só é quitado após confirmação do recebimento. |
| RF-11 | Controlar o ciclo do pedido por transições explícitas. | Uma transição permitida altera o estado uma vez; uma transição inválida retorna erro e preserva o estado anterior. |
| RF-12 | Registrar expedição e entrega. | A expedição consome as unidades físicas e libera a reserva correspondente uma única vez; a entrega atualiza o pedido sem nova baixa de estoque. |
| RF-13 | Permitir que um funcionário cancele um pedido antes da expedição, informando obrigatoriamente o motivo. | O sistema registra motivo, responsável e data; libera reservas uma única vez e cancela o título pendente, quando houver. Um pedido expedido não é cancelado diretamente por esse fluxo. |
| RF-14 | Gerar e consultar título a receber associado ao pedido. | Ao confirmar a reserva, é criado um único título pendente vinculado ao pedido. Ele permanece pendente até a confirmação do pagamento, quando é quitado. |
| RF-15 | Publicar eventos internos para ações posteriores do fluxo. | Eventos de mudanças relevantes podem acionar simulações de expedição e emissão de nota, sem duplicar os efeitos de negócio. |
| RF-16 | Consultar o histórico de movimentações de estoque por SKU. | Entradas, reservas, liberações, saídas e avarias podem ser rastreadas até a operação de origem. |
| RF-17 | Cadastrar e consultar clientes e fornecedores usados nas operações. | O usuário escolhe o papel da contraparte e informa o nome; o sistema cria identificador interno. Código externo é opcional e só é necessário para integrar/importar dados de outro sistema. Cada compra e venda referencia uma contraparte identificável. |
| RF-20 | Localizar cadastros para usá-los em operações. | O funcionário consegue pesquisar produto por SKU/nome e cliente/fornecedor por nome, distinguir resultados e selecioná-los; não precisa digitar IDs internos ou SKU em operações. |
| RF-21 | Consultar pedidos pelo identificador do pedido e oferecer formas de localizar o pedido antes de selecioná-lo. | A pessoa usuária localiza o pedido por filtros legíveis, como cliente, período ou estado; a interface usa o ID internamente para recuperar o pedido. |
| RF-18 | Registrar a origem e o autor de cada compra e venda. | Uma compra mostra o usuário interno que a registrou; uma venda mostra se foi criada pelo cliente, por um usuário interno ou por uma integração identificada, com data e referência da origem. |
| RF-19 | Encaminhar ao supervisor solicitações de cancelamento fora da regra comum. | A solicitação registra pedido, motivo, solicitante e data; não altera automaticamente o pedido, o estoque ou o título. A decisão e os efeitos de cada tipo de exceção serão especificados antes da implementação desse fluxo. |

## Regras de domínio

| ID | Regra |
| --- | --- |
| RD-01 | Quantidades de compra, venda, reserva, expedição e avaria devem ser positivas e inteiras, salvo decisão explícita de trabalhar com unidades fracionárias. |
| RD-02 | Custos e valores monetários não podem ser negativos e devem usar representação decimal exata, com moeda e regra de arredondamento definidas. |
| RD-03 | O saldo físico e o saldo reservado nunca podem ser negativos; o reservado não pode superar o físico. |
| RD-04 | Toda mudança de estoque deve gerar movimentação append-only vinculada à causa; uma movimentação registrada não é editada ou excluída. Correções usam uma nova movimentação compensatória. |
| RD-05 | O saldo disponível usado para aprovar uma reserva deve ser lido e alterado dentro de uma transação protegida contra concorrência. Reservas simultâneas não podem comprometer a mesma unidade duas vezes. |
| RD-06 | Uma compra recebida só pode afetar estoque e custo uma vez, mesmo após repetição da requisição. |
| RD-07 | A reserva de um pedido com vários itens é indivisível: se um item falhar, nenhuma reserva do pedido é confirmada. |
| RD-08 | O pedido segue uma máquina de estados explícita. Caminho principal proposto: `CRIADO → RESERVADO → PAGO → EXPEDIDO → ENTREGUE`. `CANCELADO` é terminal e pode ser alcançado pelo fluxo comum antes de `EXPEDIDO`. Pedidos expedidos exigem análise de exceção, sem transição automática para `CANCELADO`. |
| RD-09 | Pagamento, expedição, entrega e cancelamento não podem produzir efeitos duplicados quando uma operação é repetida. |
| RD-10 | A reserva confirmada gera um único título a receber pendente para o pedido. O título só é quitado após confirmação do pagamento, sem duplicidade em tentativas repetidas. O cancelamento de pedido ainda não pago cancela o título pendente. |
| RD-11 | Falhas em eventos internos não podem deixar pedido, estoque e financeiro em estados contraditórios. A fronteira transacional e a política de repetição serão especificadas por evento. |
| RD-12 | Toda ordem de compra deve estar vinculada a um fornecedor identificado antes de ser confirmada ou recebida; toda venda deve estar vinculada a um cliente identificado antes de ser reservada, paga ou concluída. Não são permitidas compras ou vendas com contraparte anônima. O vínculo deve permanecer consultável no histórico mesmo se o cadastro da contraparte mudar. |
| RD-13 | Toda compra deve registrar o usuário interno que a criou. Toda venda deve registrar sua origem e seu autor identificável, que pode ser o cliente, um usuário interno ou uma integração. Esses vínculos não podem ser apagados ou trocados silenciosamente após a operação; correções precisam deixar histórico. A venda comum não depende de aprovação manual. |
| RD-14 | Um funcionário identificado pode cancelar um pedido ainda não expedido apenas com motivo informado. O cancelamento registra autor e data, não pode ser repetido com novos efeitos e deve preservar o histórico. Solicitações fora dessa regra vão ao supervisor e não executam efeitos de cancelamento antes de uma decisão. |

## Requisitos não funcionais

| ID | Requisito | Como verificar |
| --- | --- | --- |
| RNF-01 | Consistência sob concorrência. | Teste de integração com várias reservas simultâneas para o mesmo SKU: a soma aprovada não supera o disponível inicial. |
| RNF-02 | Atomicidade das operações críticas. | Testes que provocam falha durante recebimento, reserva, pagamento e expedição e confirmam ausência de efeitos parciais. |
| RNF-03 | Idempotência de requisições suscetíveis a repetição. | Testes com chave repetida e com chave reutilizada para conteúdo diferente, incluindo tentativas concorrentes. |
| RNF-04 | Auditabilidade do estoque e das operações. | Cada alteração de saldo é explicável por movimentações persistidas com data, tipo, quantidade e referência de origem; compras e vendas exibem seu autor ou origem, e correções preservam o histórico. |
| RNF-05 | Reprodutibilidade do ambiente. | O projeto e o PostgreSQL podem ser iniciados pelas instruções documentadas com Docker Compose; migrações criam o esquema esperado. |
| RNF-06 | Testes automatizados das regras essenciais. | JUnit cobre regras isoladas; Testcontainers executa os cenários transacionais e de concorrência contra PostgreSQL real. |
| RNF-07 | Separação entre módulos de compras, estoque, vendas e financeiro. | Dependências entre módulos são explícitas e os eventos internos têm contratos documentados. |
| RNF-08 | Diagnóstico de falhas. | Operações críticas registram erros e referências de pedido/operação sem expor dados sensíveis desnecessários. |
| RNF-09 | Desempenho mensurável. | Medir latência e vazão dos fluxos críticos antes de fixar metas numéricas ou introduzir cache. Redis não integra a versão inicial. |
| RNF-10 | Documentação para execução e avaliação. | Um leitor consegue entender o fluxo, executar o projeto, reproduzir testes e consultar as decisões técnicas sem depender de explicação oral. |
| RNF-11 | Prevenir erros de entrada evitáveis na interface que consumir a API. | Cadastros existentes são pesquisados e selecionados, identificadores são preenchidos pela integração, valores numéricos são validados e um resumo é apresentado antes de confirmar operações. |

## Pontos para decisão do autor

1. **Exceções de cancelamento:** após pagamento, qual procedimento de estorno e qual status do título? Após expedição, haverá devolução em vez de cancelamento? Quais decisões o supervisor poderá tomar e quais exigirão execução manual?
2. **Pagamento e faturamento:** `PAGO` significa liquidação integral? Pagamento parcial entra no escopo? Como identificar a confirmação externa do recebimento?
3. **Custo médio:** como tratar estoque zerado, frete, impostos, descontos, avarias e arredondamento? A fórmula de RF-04 é uma hipótese inicial.
4. **Recebimento:** uma ordem de compra pode ser recebida parcialmente ou apenas por completo na primeira versão?
5. **Emissão de nota e expedição:** serão apenas simulações locais? Qual evento as inicia, e o que ocorre quando falham?
6. **Segurança da API:** haverá autenticação e papéis de usuário na primeira versão? Quais dados precisam ser protegidos?
7. **Metas operacionais:** quais volumes e tempos de resposta queremos demonstrar nos testes de carga?
8. **Identificação e responsabilidade:** quais dados mínimos identificam cliente, fornecedor, usuário interno e integração? Como a API comprovará a identidade de quem cria cada operação?

As escolhas ainda abertas não devem ser tratadas como funcionalidades concluídas. Após a revisão, estes requisitos podem ser desdobrados em casos de uso e cenários de teste.
