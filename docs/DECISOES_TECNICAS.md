# Decisões técnicas — OpenERP Core

Este documento explica as escolhas iniciais do projeto em linguagem direta. Elas são hipóteses de arquitetura, sujeitas a revisão conforme os casos de uso e os testes tragam evidências. A intenção é mostrar o raciocínio por trás da implementação, inclusive os custos e as alternativas consideradas.

## Critério geral

O projeto é um backend de compras, estoque, pedidos e financeiro. A prioridade é manter dados consistentes sob concorrência, permitir auditoria e demonstrar as regras por meio de testes reproduzíveis. A stack deve servir a esses objetivos sem adicionar infraestrutura apenas para parecer sofisticada.

## Linguagem e aplicação

### Java 21+

**Por que considerar:** foi a escolha inicial do autor por ser uma linguagem amplamente usada em sistemas corporativos. Também permite construir o projeto com tipagem estática e um ecossistema de bibliotecas para aplicações backend.

**Alternativas:** Kotlin ou C# também poderiam atender ao domínio. Java permanece como escolha do projeto para concentrar o aprendizado e demonstrar domínio de uma stack coerente. Isso não significa que as alternativas sejam tecnicamente inferiores.

### Spring Boot 3+

**Papel:** montar a API REST, organizar componentes e integrar persistência, validação e testes.

**Por que considerar:** oferece um caminho conhecido para desenvolver os módulos de negócio em Java, reduzindo configuração inicial.

**Alternativas:** Quarkus e Micronaut são opções Java viáveis. A preferência inicial pelo Spring Boot é prática e ligada ao objetivo de construir e explicar o fluxo completo; o framework deve ser avaliado pelo que entrega ao projeto, não por popularidade isolada.

**Virtual Threads:** podem ser experimentadas para operações bloqueantes de I/O. Seu uso será medido; não substituem transações, locks ou controle de concorrência do banco.

## Dados e consistência

### PostgreSQL

**Papel:** fonte de verdade para produtos, compras, saldos, movimentações, pedidos e títulos financeiros.

**Por que considerar:** o domínio tem relações entre entidades e exige transações. A reserva de estoque deve ser protegida por uma operação transacional no banco, com testes de concorrência.

**Alternativas:** MySQL é outro banco relacional possível. Um banco documental poderia armazenar parte dos dados, mas não simplifica as invariantes transacionais centrais deste projeto. A comparação deve ser revista se surgirem requisitos que mudem esse cenário.

### Spring Data JPA e Hibernate

**Papel:** mapear entidades Java para tabelas e implementar acesso aos dados.

**Por que considerar:** integração direta com a stack e produtividade para operações comuns.

**Alternativas:** JDBC ou jOOQ oferecem maior controle explícito sobre SQL. Mesmo com JPA, consultas críticas, locks e limites transacionais precisam ser inspecionados; se o mapeamento atrapalhar esses pontos, SQL explícito é uma opção.

**Decisão da primeira entrega:** usar JPA nos cadastros e JdbcTemplate com SQL explícito no recebimento e na reserva. Isso deixa a ordem dos bloqueios e das gravações auditável no código. Os dois acessos participam das transações gerenciadas pelo Spring.

### Flyway

**Papel:** manter migrações versionadas da estrutura do banco para reproduzir sua evolução.

**Alternativas:** Liquibase ou scripts SQL executados manualmente. Flyway foi proposto pela simplicidade do fluxo de migrações; scripts manuais exigiriam mais disciplina para garantir a mesma versão de esquema em todos os ambientes.

## Redis e eventos

### Redis — não utilizar na versão inicial

**Decisão:** não incluir Redis na arquitetura inicial. Redis opera no servidor; não é o local onde os cookies do navegador ficam armazenados.

**Motivo:** a reserva de estoque exige uma decisão transacional com dados atuais. Um saldo em cache pode ficar desatualizado após compras, reservas ou cancelamentos. PostgreSQL será a fonte de verdade e aplicará a garantia de consistência; adicionar Redis agora criaria outro serviço, regras de invalidação de cache e novos modos de falha sem uma necessidade medida. Para a primeira versão, eventos internos do monólito são suficientes para desacoplar o fluxo previsto.

**Reavaliação:** se testes de carga ou métricas de produção mostrarem consultas de leitura custosas, Redis poderá ser considerado para dados apropriados, como catálogo, com uma política explícita de atualização ou expiração. Não será usado como autoridade para decidir reservas. Se houver processamento assíncrono com exigência de entrega confiável, será necessário especificar persistência, repetição e tratamento de falhas antes de escolher uma fila.

## Testes e execução

### JUnit 5 e Mockito

**Papel:** JUnit executa testes Java; Mockito permite substituir dependências por objetos controlados em testes de regras isoladas.

**Alternativas:** outros frameworks de teste e implementações manuais de dependências falsas. A escolha inicial mantém os testes unitários simples, mas mocks não comprovam o comportamento real do banco nem de transações concorrentes.

### Testcontainers

**Papel:** iniciar instâncias temporárias de PostgreSQL em contêineres durante testes de integração. O objetivo é testar contra o banco real, com ambiente reproduzível.

**Alternativas:** serviços instalados localmente ou bancos em memória. Instalações locais variam entre máquinas; bancos em memória podem se comportar de forma diferente do PostgreSQL nos pontos críticos de concorrência e SQL. Testcontainers exige um ambiente de contêineres disponível para rodar esses testes.

### Docker e Docker Compose

**Papel:** empacotar e subir a aplicação e os serviços necessários com uma configuração compartilhada.

**Alternativas:** instalar e iniciar cada serviço manualmente ou usar um ambiente gerenciado. Compose foi proposto para facilitar a demonstração e a reprodução local. Isso não significa execução automática em qualquer máquina: ela ainda precisa de um mecanismo de contêineres compatível e recursos suficientes.

## Próxima validação

Antes de fechar a arquitetura, detalhar os casos de uso e critérios de aceite. Em seguida, implementar e testar o fluxo mínimo de compra, entrada de estoque, pedido e reserva concorrente. Os resultados devem confirmar ou alterar as escolhas acima. Decisões específicas que mereçam análise mais profunda poderão ganhar registros próprios em `docs/adr`.
