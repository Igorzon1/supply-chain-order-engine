# Casos de uso — OpenERP Core V1

**Estado:** proposta detalhada para orientar a primeira entrega. Complementa o [escopo V1](ESCOPO_V1.md), os [requisitos gerais](REQUISITOS.md) e as [notas da implementação](IMPLEMENTACAO_V1.md). Os comportamentos descritos aqui são o alvo funcional; consulte as notas de implementação para saber o que já existe no código e o que ainda não foi validado em execução.

## Atores e convenções

- **Funcionário:** usuário interno autenticado que cadastra entidades, registra compras, recebe mercadorias e cria pedidos em nome de clientes.
- **Cliente:** contraparte da venda, vinculada ao pedido. Na V1 não autentica nem chama a API diretamente.
- **API:** recebe as chamadas e registra o funcionário autenticado como autor.
- **PostgreSQL:** armazena cadastros, operações, saldos e movimentações dentro de transações.

Todos os casos operacionais exigem um funcionário autenticado. Na implementação atual, o mecanismo é HTTP Basic com uma conta de demonstração local.

**Experiência de uso:** este é um projeto backend e não inclui telas nesta versão. Os casos descrevem como uma futura tela ou cliente de API deve operar. A pessoa usuária pesquisa e seleciona cadastros; identificadores internos são enviados pela integração por trás da tela, nunca digitados manualmente. Onde for necessário preencher quantidade ou custo, a interface valida e mostra contexto antes de confirmar.

## UC-01 — Cadastrar entidades

**Ator principal:** Funcionário.

**Objetivo:** cadastrar produtos e contrapartes para poder referenciá-los em compras e pedidos.

**Pré-condições:** funcionário autenticado.

**Fluxo principal:**

1. Antes de cadastrar, o funcionário pesquisa para verificar se o produto ou contraparte já existe.
2. Para cadastrar produto, o funcionário informa o nome.
3. O sistema gera um SKU único e estável, sem depender do nome, e mostra o SKU criado.
4. Para cadastrar uma contraparte, o funcionário escolhe `cliente` ou `fornecedor` e informa o nome.
5. O sistema gera um identificador interno e devolve o cadastro. Um código externo pode ser informado como referência opcional.
6. O funcionário pesquisa e seleciona o cadastro pelo nome ou SKU; a integração usa o identificador interno por trás da tela.

**Alternativas e erros:**

- Nome ausente ou em branco: rejeitar com erro de validação.
- Colisão rara na geração de SKU: gerar outro SKU antes de concluir o cadastro.
- Código externo opcional já usado por outra contraparte do mesmo tipo: rejeitar para evitar ambiguidade.
- Nomes iguais são permitidos; o funcionário distingue as contrapartes pelos detalhes disponíveis e pelo identificador interno.
- Identificador consultado não encontrado: informar que o cadastro não existe.

**Critérios de aceite:** produto é criado sem o funcionário digitar SKU; o SKU gerado é único e pode ser consultado; o funcionário pode escolher cadastrar cliente ou fornecedor; o código externo não é obrigatório para operações locais; operações posteriores referenciam registros existentes.

**Código externo:** é uma referência atribuída fora deste sistema, por exemplo o código de um cliente no ERP antigo ou o identificador usado por um fornecedor. Não é o ID interno gerado pelo banco, não é automaticamente CPF/CNPJ e não precisa ser preenchido quando o OpenERP Core é a fonte dos cadastros. Nesta V1, o formulário básico deve pedir somente nome e papel; códigos externos ficam para importação ou integração futura.

**Ponto de domínio a decidir:** uma mesma empresa pode atuar como cliente e fornecedor. O modelo atual mantém cadastros separados; antes de implementar o formulário simplificado, decidir se a pessoa terá dois cadastros ou um cadastro único com os dois papéis.

## UC-02 — Registrar ordem de compra

**Ator principal:** Funcionário.

**Objetivo:** registrar produtos adquiridos de um fornecedor e seus custos.

**Pré-condições:** funcionário autenticado; fornecedor e produtos cadastrados.

**Fluxo principal:**

1. O funcionário inicia uma ordem e seleciona um fornecedor já cadastrado.
2. O funcionário localiza produtos já cadastrados por SKU ou nome e adiciona cada produto à ordem.
3. Para cada item, informa a quantidade que pretende comprar e o custo unitário indicado pelo fornecedor.
4. O sistema mostra SKU, nome, estoque atual e custo médio como referência.
5. O sistema valida fornecedor e produtos existentes, quantidade positiva, custo não negativo e ausência de itens repetidos.
6. Antes de salvar, o sistema mostra um resumo do fornecedor e das linhas da ordem.
7. O sistema salva ordem, itens, autor e data e devolve o identificador da ordem.

**Alternativas e erros:**

- Fornecedor inexistente: rejeitar sem criar ordem.
- Produto inexistente, item repetido, quantidade inválida ou custo negativo: rejeitar a requisição inteira.
- Custo informado diferente do custo médio atual: mostrar a diferença como contexto; não substituir o custo informado, pois ele deve refletir o documento/preço acordado com o fornecedor e pode legitimamente mudar.
- Quantidade comprada diferente do estoque atual não é erro: a quantidade da ordem representa entrada futura e será somada ao saldo físico quando recebida.
- O usuário não digita SKU nem ID do produto: escolhe um produto do catálogo; a API recebe sua referência interna.

**Critérios de aceite:** ordem válida preserva fornecedor, autor, produtos escolhidos do catálogo, quantidades e custos; o custo médio aparece como referência e não substitui o custo informado do fornecedor; requisição inválida não deixa ordem parcialmente criada.

## UC-03 — Receber uma ordem de compra

**Ator principal:** Funcionário.

**Objetivo:** confirmar que a ordem foi recebida por completo e lançar os produtos no estoque.

**Pré-condições:** ordem existente e ainda não recebida; produtos relacionados têm cadastro e saldo inicial.

**Fluxo principal:**

1. O funcionário abre a ordem pelo localizador de compras e confere as quantidades físicas contra as quantidades da ordem.
2. Na V1, o recebimento é completo: todas as quantidades recebidas precisam corresponder às quantidades ordenadas.
3. O sistema bloqueia a ordem durante a confirmação e verifica se já foi recebida.
4. Para cada item, o sistema calcula o novo saldo físico e o custo médio ponderado.
5. O sistema atualiza o saldo e o custo médio e insere uma movimentação `RECEIPT` vinculada à compra e ao funcionário.
6. O sistema marca a ordem como recebida e devolve os dados atualizados.

**Alternativas e erros:**

- Ordem inexistente: informar que não foi encontrada.
- Ordem já recebida: devolver seu estado atual sem somar estoque nem criar novas movimentações.
- Quantidade recebida divergente da ordem: não confirmar o recebimento integral; recebimento parcial ou divergência exigem processo de exceção futuro.
- Falha ao processar qualquer item: desfazer a operação inteira; nenhum saldo, custo ou ledger fica parcialmente alterado.

**Regra de custo da V1:** `(saldo físico anterior × custo médio anterior + quantidade recebida × custo unitário) ÷ novo saldo físico`, arredondado para quatro casas decimais com `HALF_UP`. Com saldo anterior zero, o custo médio passa a ser o custo da entrada.

**Critérios de aceite:** recebimento completo e atômico; uma única entrada por produto e compra; custo médio calculado pela fórmula; saldo e ledger atualizados na mesma transação.

## UC-04 — Consultar saldo de estoque

**Ator principal:** Funcionário.

**Objetivo:** verificar as quantidades e o custo médio de um produto.

**Pré-condições:** funcionário autenticado; SKU cadastrado.

**Fluxo principal:**

1. O funcionário pesquisa o produto pelo SKU ou nome e seleciona o resultado.
2. A interface envia a referência do produto selecionado; o sistema retorna saldo físico, reservado, disponível e custo médio.

**Regra:** `disponível = físico − reservado`.

**Alternativas e erros:** SKU inexistente resulta em não encontrado.

**Critérios de aceite:** saldo disponível corresponde aos dois saldos-base; o usuário não precisa digitar ID interno; a consulta não mistura dados de pedido ou financeiro.

## UC-05 — Consultar movimentações de estoque

**Ator principal:** Funcionário.

**Objetivo:** rastrear as entradas e reservas registradas para um produto.

**Pré-condições:** funcionário autenticado; SKU cadastrado.

**Fluxo principal:** o funcionário pesquisa produto por SKU ou nome e seleciona um resultado; a integração consulta usando a referência do produto e o sistema retorna em ordem cronológica tipo, deltas, operação de origem, autor e data.

**Alternativas e erros:** SKU inexistente resulta em não encontrado.

**Critérios de aceite:** cada movimentação pode ser relacionada ao produto e à operação que a originou; esta consulta é separada da consulta de saldo.

## UC-06 — Consultar pedido

**Ator principal:** Funcionário.

**Objetivo:** consultar os dados e o estado de uma venda.

**Pré-condições:** funcionário autenticado; ID do pedido conhecido.

**Fluxo principal:** o funcionário localiza o pedido por uma lista ou pesquisa (por exemplo, cliente, período ou estado) e seleciona o resultado; a integração consulta pelo ID interno e o sistema retorna cliente, estado, autor e itens com quantidades.

**Alternativas e erros:** ID inexistente resulta em não encontrado.

**Critérios de aceite:** a consulta usa o ID do pedido por trás da seleção e não depende de SKU; o funcionário não precisa decorar nem digitar IDs; os dados de estoque e os dados financeiros não são misturados nesta resposta.

## UC-07 — Criar pedido e reservar estoque

**Ator principal:** Funcionário.

**Objetivo:** registrar uma venda para um cliente e comprometer unidades disponíveis para esse pedido.

**Pré-condições:** funcionário autenticado; cliente e produtos cadastrados; cada item tem quantidade positiva; requisição contém `Idempotency-Key`.

**Fluxo principal:**

1. O funcionário pesquisa e seleciona um cliente cadastrado; a integração envia sua referência interna.
2. O funcionário pesquisa produtos por SKU ou nome e seleciona os itens. A interface mostra disponibilidade e permite informar quantidade inteira positiva.
3. A interface avisa se a quantidade excede a disponibilidade consultada, consolida linhas repetidas do mesmo produto e mostra um resumo antes de confirmar.
4. O cliente da API gera automaticamente uma `Idempotency-Key` para esta intenção e a preserva se repetir a chamada. O funcionário não digita essa chave.
5. O sistema valida o cliente, os produtos e a estrutura dos itens.
6. O sistema bloqueia a chave para serializar tentativas simultâneas com a mesma chave.
7. Se a chave ainda não foi usada, o sistema cria o pedido e seus itens.
8. O sistema bloqueia as linhas de saldo dos produtos em ordem crescente de ID.
9. Se houver quantidade disponível para todos os itens, o sistema aumenta os saldos reservados e cria movimentações `RESERVATION` para cada produto.
10. O sistema muda o estado do pedido de `CRIADO` para `RESERVADO` dentro da mesma transação e devolve o pedido.

**Alternativas e erros:**

- Sem cliente cadastrado, produto inexistente, item repetido, quantidade inválida ou chave ausente/inválida: rejeitar.
- Saldo insuficiente para qualquer item: rejeitar o pedido inteiro e desfazer criação, reservas e movimentações parciais.
- O estoque pode mudar entre consulta e confirmação: o servidor valida o saldo novamente dentro da transação e rejeita sem efeitos parciais se outro pedido consumir a disponibilidade primeiro.
- Falha inesperada antes da confirmação da transação: desfazer todos os efeitos.

**Critérios de aceite:** pedido associa cliente e autor; o usuário escolhe cliente e produtos em vez de digitar IDs ou SKUs; todos os itens reservam juntos ou nenhum reserva; não há saldo reservado acima do físico; o pedido concluído aparece como `RESERVADO`.

## UC-08 — Repetir uma tentativa de criação de pedido

**Ator principal:** cliente da API enviando uma nova tentativa ou repetição da chamada em nome do funcionário.

**Objetivo:** evitar pedido e reserva duplicados quando a mesma chamada é reenviada por timeout, falha de rede ou repetição automática.

**Regra central:** idempotência identifica **a tentativa**, não compara o conteúdo para proibir pedidos iguais. Dois pedidos intencionais com os mesmos cliente e itens são permitidos quando enviados com chaves diferentes.

**Fluxos:**

1. **Mesma chave e mesmo conteúdo:** devolver o pedido original, sem nova reserva.
2. **Mesma chave e conteúdo diferente:** rejeitar com conflito; a chave já representa outra operação.
3. **Chaves diferentes e conteúdo igual:** criar um novo pedido e fazer uma nova reserva se houver saldo.
4. **A primeira tentativa falhou antes de confirmar:** como a transação foi desfeita e a chave não ficou gravada, uma repetição poderá ser validada novamente.

**Conteúdo comparado na V1:** autor autenticado, cliente e lista ordenada de produto/quantidade. A chave é única globalmente e não expira.

**Critérios de aceite:** repetir uma chamada concluída com sua chave original retorna o mesmo identificador; a reserva não cresce; reutilização com conteúdo diferente retorna conflito; uma nova intenção pode usar uma nova chave mesmo com conteúdo idêntico.

## UC-09 — Proteger reservas concorrentes

**Ator principal:** vários funcionários autenticados enviando requisições simultâneas.

**Objetivo:** garantir que pedidos concorrentes não comprometam as mesmas unidades.

**Cenário de aceite:** há uma unidade disponível de um SKU e dois pedidos diferentes tentam reservar uma unidade ao mesmo tempo. Uma única reserva pode ser confirmada; a outra recebe saldo insuficiente. O saldo reservado termina em uma unidade, sem valor negativo ou acima do físico.

**Cenário com vários SKUs:** dois pedidos contêm os mesmos SKUs em ordens diferentes. A aplicação bloqueia os produtos em ordem determinística. Se qualquer item de um pedido não tiver saldo, todo aquele pedido falha sem deixar reservas parciais.

**Critérios de aceite:** a regra é verificada com múltiplas conexões contra PostgreSQL real; o saldo final e o ledger correspondem apenas às reservas confirmadas.

## Fora destes casos na V1

Pagamento, título a receber, consulta financeira por ID de pedido, cancelamento por funcionário, motivo de cancelamento, aprovação excepcional por supervisor, expedição, entrega, devolução, avaria, recebimento parcial, custos adicionais e chamadas autenticadas diretamente por clientes. Quando o financeiro entrar no escopo, sua consulta será pelo ID do pedido ou do título relacionado, sem agregar essa informação à consulta de estoque.

## Prevenção de erros na interface que consumir a API

- IDs internos, SKU gerado e chave de idempotência são valores de integração; não devem ser campos de digitação livre para o funcionário.
- Produtos, clientes e fornecedores existentes devem ser localizados por pesquisa e escolhidos de uma lista com informações legíveis.
- Quantidade de venda deve ser positiva e comparada com a disponibilidade. O servidor valida novamente dentro da transação para cobrir vendas concorrentes.
- Quantidade de compra representa o que se pretende adquirir e pode ser diferente do estoque atual. No recebimento integral, a divergência deve ser destacada por produto e bloquear uma confirmação silenciosa.
- Custo da compra deve refletir a cotação ou documento do fornecedor. O custo médio atual é referência para perceber variações, mas não substitui nem bloqueia um preço legítimo.
- Antes da confirmação da compra ou do pedido, mostrar um resumo de cliente/fornecedor, produtos, quantidades e custos informados.

## Observações de implementação

- Estes casos descrevem o comportamento esperado, não afirmam que a execução já foi validada. A situação dos testes está em [IMPLEMENTACAO_V1.md](IMPLEMENTACAO_V1.md).
- A implementação de pedidos inclui a chave de idempotência; pedidos com conteúdo igual e chaves diferentes são pedidos distintos.
- A aplicação usa JPA para cadastros e SQL explícito via JdbcTemplate nos fluxos transacionais de estoque.
- As regras estão concentradas nos controllers no protótipo atual. A extração para serviços de aplicação é um refino arquitetural a fazer.
- A V1 não contém a máquina de estados completa nem endpoints de transição manual; `CRIADO → RESERVADO` ocorre dentro da mesma transação.
- Os fluxos definem o comportamento alvo. O protótipo atual ainda pede SKU e código externo manualmente, recebe linhas de compra com `productId` e não oferece busca de produtos por nome/SKU nem conferência de quantidade física no recebimento; essas diferenças devem ser tratadas antes de considerar os casos de cadastro e compra concluídos.
- A criação do pedido já exige a chave na chamada à API; uma interface deve gerá-la automaticamente e preservá-la apenas para repetição da mesma tentativa.
