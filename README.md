# Hero Schema

Aplicação Kotlin com Spring Boot para gerenciamento de schema e recursos de autenticação/tenant em PostgreSQL.

## Informacoes basicas

- **Nome do projeto:** `heroschema`
- **Stack principal:** Kotlin, Spring Boot, Spring MVC, Thymeleaf, JPA/JDBC
- **Banco de dados:** PostgreSQL
- **Java:** 21 (toolchain configurada no Gradle)

## Como executar

### Pre-requisitos

- Java 21 instalado
- PostgreSQL em execucao

### 1) Configure as variaveis de ambiente

No PowerShell:

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/seu_banco"
$env:DB_USER="seu_usuario"
$env:DB_PASSWORD="sua_senha"

```
```ts
DB_PASSWORD=mypassword;
DB_URL=jdbc:postgresql://localhost:5432/stain_db;
DB_USER=myuser
```



### 2) Inicie a aplicacao

No Windows:

```powershell
.\gradlew.bat bootRun
```

No Linux/macOS:

```bash
./gradlew bootRun
```

## Porta padrao

A aplicacao sobe por padrao na porta `8081` (configurada em `src/main/resources/application.yaml`).

URL local:

- `http://localhost:8081`

## Comandos uteis

```powershell
.\gradlew.bat test
.\gradlew.bat build
```


## 📄 Licença

heroSchema é licenciado sob **GNU Affero General Public License v3.0**.

Você pode:

- Usar comercialmente
- Distribuir
- Modificar
- Usar em projetos privados

Você deve:

- Manter copyright e licença
- Divulgar fonte se modificar
- **Se oferecer como serviço (SaaS), abrir o código fonte**

Leia a [LICENSE](LICENSE) completa.
