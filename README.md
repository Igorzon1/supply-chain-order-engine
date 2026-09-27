# Supply Chain & Order-to-Cash Engine

Primeira entrega do backend de compras, estoque e vendas. O recorte e seus motivos estão em [docs/ESCOPO_V1.md](docs/ESCOPO_V1.md); os casos de uso detalhados estão em [docs/CASOS_DE_USO_V1.md](docs/CASOS_DE_USO_V1.md); o modelo conceitual está em [docs/MODELO_DOMINIO.md](docs/MODELO_DOMINIO.md); as decisões, os limites e o estado de validação estão em [docs/IMPLEMENTACAO_V1.md](docs/IMPLEMENTACAO_V1.md); os requisitos gerais estão em [docs/REQUISITOS.md](docs/REQUISITOS.md).

## Executar

Requer Docker e Docker Compose em execução. Na raiz do projeto:

```sh
cp .env.example .env
# Edite .env e defina senhas locais fortes para DB_PASSWORD e APP_STAFF_PASSWORD.
docker compose up --build
```

A API estará em `http://localhost:8080`. O ambiente local usa autenticação HTTP Basic. O usuário padrão é `staff`, e a senha deve ser definida em `APP_STAFF_PASSWORD` no arquivo `.env`. Esta primeira entrega registra esse usuário como autor das operações. Não há login de cliente nem autorização por papel nesta etapa.

Para executar os testes diretamente no host, é necessário JDK 21+, Maven e Docker ativo:

```sh
mvn test
```

No Windows, também é possível rodar Maven e JDK 21 dentro de um contêiner, sem instalá-los no host. Execute na raiz do projeto com Docker Desktop ativo:

```powershell
$projectPath = (Get-Location).Path
docker run --rm `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  --mount "type=bind,source=$projectPath,target=/workspace" `
  --mount "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock" `
  --mount "type=volume,source=supply-chain-maven-cache,target=/root/.m2" `
  -w /workspace `
  maven:3.9-eclipse-temurin-21 mvn -B -ntp test
```

O volume `supply-chain-maven-cache` guarda as dependências Maven entre execuções. O socket permite que o Testcontainers inicie o PostgreSQL temporário; `TESTCONTAINERS_HOST_OVERRIDE` permite que o contêiner de teste acesse as portas publicadas pelo Docker Desktop.

Testcontainers inicia um PostgreSQL temporário. Redis não faz parte desta entrega.

## Fluxo mínimo da API

Os exemplos abaixo descrevem a API implementada no protótipo atual, sem interface gráfica. Os casos de uso descrevem uma interface futura que pesquisa e seleciona cadastros, gera SKU e chave de idempotência automaticamente e torna o código externo opcional; essas alterações ainda precisam chegar ao código.

Todas as chamadas exigem HTTP Basic. Crie os cadastros nesta ordem e use os IDs retornados:

Para usar os exemplos em um terminal Bash, carregue as variáveis do arquivo `.env` primeiro:

```sh
set -a
. ./.env
set +a
```

```sh
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" -H 'Content-Type: application/json' -d '{"externalCode":"FOR-1","name":"Fornecedor"}' http://localhost:8080/api/suppliers
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" -H 'Content-Type: application/json' -d '{"externalCode":"CLI-1","name":"Cliente"}' http://localhost:8080/api/customers
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" -H 'Content-Type: application/json' -d '{"sku":"SKU-1","name":"Produto"}' http://localhost:8080/api/products
```

Depois, registre e receba uma compra, usando os IDs reais de fornecedor e produto:

```sh
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" -H 'Content-Type: application/json' -d '{"supplierId":1,"lines":[{"productId":1,"quantity":10,"unitCost":12.50}]}' http://localhost:8080/api/purchases
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" -X POST http://localhost:8080/api/purchases/1/receive
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" http://localhost:8080/api/inventory/SKU-1
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" http://localhost:8080/api/inventory/SKU-1/movements
```

Por fim, crie o pedido com uma chave de idempotência escolhida pelo cliente da API:

```sh
curl -u "${APP_STAFF_USER:-staff}:${APP_STAFF_PASSWORD}" -H 'Content-Type: application/json' -H 'Idempotency-Key: exemplo-pedido-1' -d '{"customerId":1,"lines":[{"productId":1,"quantity":2}]}' http://localhost:8080/api/orders
```

Repetir essa última chamada com a mesma chave e o mesmo conteúdo devolve o pedido existente. A mesma chave com conteúdo diferente retorna conflito. A chave fica persistida sem prazo de expiração nesta primeira entrega.

## Regras implementadas

- O recebimento é completo e só altera o estoque uma vez.
- O custo médio usa `(custo médio anterior × saldo físico anterior + custo da entrada × quantidade recebida) ÷ novo saldo físico`, arredondado para quatro casas decimais com `HALF_UP`. Frete, impostos e descontos ficam fora do cálculo inicial.
- O saldo disponível é `físico − reservado`. A reserva de vários itens confirma todos ou nenhum.
- Compras e pedidos travam linhas de saldo em ordem de ID de produto dentro de transações PostgreSQL. O livro-razão aceita apenas inserções e preserva a referência da operação.
- Um pedido criado com sucesso termina em `RESERVADO`. `CRIADO` é um estado transitório na mesma transação; não há endpoint para avançar manualmente o status nesta entrega.
- A camada de cadastros usa Spring Data JPA. Os fluxos transacionais de estoque usam SQL explícito via JdbcTemplate para tornar os locks e a ordem das operações visíveis.

## Fora da primeira entrega

Pagamento, títulos a receber, cancelamento, supervisor, expedição, entrega, avaria, recebimento parcial e Redis permanecem planejados, mas não estão implementados. A configuração de autenticação atual é adequada apenas para desenvolvimento local; um sistema exposto a usuários reais exigirá gestão de identidades e HTTPS.
