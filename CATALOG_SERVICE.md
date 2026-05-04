# Catalog Translation Service

Rozšíření sidecar middlewaru o **plný překladový pipeline pro relační DB**:

```
┌─────────────┐   JDBC    ┌─────────────────────┐   API    ┌──────────┐
│  Maximo /   │ ────────► │ CatalogReader       │ ───────► │  DeepL   │
│  PostgreSQL │           │ (read-only metadata │          │  Google  │
│  Db2 / ...  │           │  + rows)            │          │  OpenAI  │
└─────────────┘           └─────────┬───────────┘          └─────┬────┘
                                    │                            │
                                    ▼                            ▼
                        ┌─────────────────────────────────────────┐
                        │ CatalogTranslationService               │
                        │ (per-record × per-target-language)      │
                        └─────────┬───────────────────────────────┘
                                  │ append-only
                                  ▼
                        ┌─────────────────────────────────────────┐
                        │ CatalogTranslationRepository (Elastic)  │
                        │ key = catalogId + recordId +            │
                        │       fieldName + targetLang            │
                        └─────────┬───────────────────────────────┘
                                  │ retrieval
                                  ▼
                        GET /catalogs/{id}/records?lang=cs|de|sk|...
```

## Co služba dělá

1. **Čte schéma** zdrojové DB přes `DatabaseMetaData` (tabulky, sloupce).
2. **Čte řádky** konfigurovaných tabulek přes `JdbcTemplate` (read-only).
3. **Překládá** každé textové pole do **všech** nakonfigurovaných cílových jazyků
   (DeepL / Google / OpenAI, přes `TranslationProvider` Strategy).
4. **Ukládá** do append-only TM, klíč: `(catalogId, recordId, fieldName, targetLang)`.
   Stejný řádek může mít překlady do **N jazyků současně**.
5. **Vystavuje** přes REST: `GET /catalogs/{id}/records?lang=cs` vrátí celý
   katalog v zvoleném jazyce.
6. **Idempotence:** opakovaný `sync` přeskočí pole, jejichž zdrojový text se
   nezměnil (úspora API kvóty).

## Konfigurace

`src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    # Pro produkci: jdbc:postgresql://maximo-db:5432/MAXIMO
    url: jdbc:h2:mem:catalogdb;MODE=PostgreSQL
    username: sa
    password:

app:
  catalog:
    id: maximo-items
    source-lang: en
    target-langs: [cs, de, sk, pl]    # libovolný počet jazyků
    tables:
      - name: ITEM
        id-column: ITEMNUM
        text-columns: [DESCRIPTION, LONGDESCRIPTION]
```

## Spuštění (demo s vestavěným H2)

```bash
mvn spring-boot:run
# Spuštěno na http://localhost:8080
# H2 obsahuje 8 demo položek (viz src/main/resources/data.sql)
```

## API příklady

Přihlášení: HTTP Basic (default `admin:admin-pwd-change-me` v dev profilu).

### 1. Inspekce schématu zdrojové DB

```bash
curl -u admin:admin-pwd-change-me \
     http://localhost:8080/api/v1/catalogs/maximo-items/metadata
```

Odpověď:

```json
{
  "catalogId": "maximo-items",
  "sourceLang": "en",
  "targetLangs": ["cs", "de", "sk", "pl"],
  "configuredTables": [
    { "name": "ITEM", "idColumn": "ITEMNUM",
      "textColumns": ["DESCRIPTION", "LONGDESCRIPTION"] }
  ],
  "discoveredTables": {
    "ITEM": ["ITEMNUM", "DESCRIPTION", "LONGDESCRIPTION", "CATEGORY", "UNIT"]
  }
}
```

### 2. Spuštění překladu (sync = blocking; vhodné pro malé katalogy)

```bash
curl -u admin:admin-pwd-change-me -X POST \
     http://localhost:8080/api/v1/catalogs/maximo-items/sync
```

Odpověď:

```json
{
  "catalogId": "maximo-items",
  "translatedCount": 64,
  "skippedCount": 0,
  "errorCount": 0,
  "elapsedMs": 1820,
  "errorMessages": []
}
```

8 řádků × 2 sloupce (`DESCRIPTION`, `LONGDESCRIPTION`) × 4 jazyky = **64 překladů**.

### 3. Asynchronní sync (pro velké katalogy)

```bash
curl -u admin:admin-pwd-change-me -X POST \
     http://localhost:8080/api/v1/catalogs/maximo-items/sync/async
# HTTP 202 + { "status": "PROCESSING" }
```

### 4. Retrieval — celý katalog v jednom jazyce

```bash
curl -u maximo:maximo-pwd-change-me \
     "http://localhost:8080/api/v1/catalogs/maximo-items/records?lang=cs"
```

Odpověď:

```json
{
  "catalogId": "maximo-items",
  "language": "cs",
  "recordCount": 8,
  "records": {
    "15240-H2500": {
      "DESCRIPTION":     "Hydraulické čerpadlo H-2500, 500 W, pro náročný provoz",
      "LONGDESCRIPTION": "<p>Hydraulické čerpadlo pro náročné průmyslové aplikace. <b>Maximální tlak</b> 40 bar, průtok 500 l/min.</p>"
    },
    "M12-1.75-ISO4014": {
      "DESCRIPTION":     "Šroub M12 x 1,75 šestihranný ISO 4014, nerezová ocel",
      "LONGDESCRIPTION": "Šestihranný šroub M12 x 1,75 mm stoupání dle ISO 4014, nerezová ocel A2-70, délka 80 mm."
    }
    /* ... */
  }
}
```

### 5. Retrieval — jeden záznam ve všech jazycích

```bash
curl -u maximo:maximo-pwd-change-me \
     http://localhost:8080/api/v1/catalogs/maximo-items/records/15240-H2500
```

Odpověď:

```json
{
  "catalogId": "maximo-items",
  "recordId": "15240-H2500",
  "translations": {
    "cs": { "DESCRIPTION": "Hydraulické čerpadlo H-2500…", "LONGDESCRIPTION": "…" },
    "de": { "DESCRIPTION": "Hydraulikpumpe H-2500…",        "LONGDESCRIPTION": "…" },
    "sk": { "DESCRIPTION": "Hydraulické čerpadlo H-2500…",  "LONGDESCRIPTION": "…" },
    "pl": { "DESCRIPTION": "Pompa hydrauliczna H-2500…",    "LONGDESCRIPTION": "…" }
  }
}
```

### 6. Statistiky

```bash
curl -u maximo:maximo-pwd-change-me \
     http://localhost:8080/api/v1/catalogs/maximo-items/stats
```

```json
{
  "catalogId": "maximo-items",
  "uniqueRecords": 8,
  "totalTranslations": 64,
  "languages": ["cs", "de", "sk", "pl"]
}
```

## SQL-proxy: drop-in transparentní vrstva

Pro existující aplikace (Maximo report, BI nástroje, custom dashboards),
které mluví přímo SQL: stačí přepnout endpoint URL z DB na sidecar.
Originální `SELECT` dotazy fungují beze změn — sidecar parsuje, vykoná
na zdrojové DB a vrátí ten samý ResultSet, ale s textovými sloupci
přeloženými do požadovaného jazyka.

```
       ┌─────────────────────┐
       │ Existing app /      │
       │ BI / report engine  │  ──── unchanged SELECT … ────►
       └─────────────────────┘
                    │
                    ▼
       POST /api/v1/sql?lang=cs
       { "query": "SELECT … FROM ITEM WHERE …" }
                    │
                    ▼
       ┌─────────────────────────────────────┐
       │  SqlProxyService                    │
       │  1. Parse SQL (JSqlParser)          │
       │  2. Execute on source DB (as-is)    │
       │  3. Substitute text-columns from TM │
       │  4. Return ES-SQL compat JSON       │
       └─────────────────────────────────────┘
```

### Pravidla substituce

- **Filtry/JOIN/ORDER BY/GROUP BY** se vyhodnotí na zdrojové (kanonické)
  DB → semantika dotazů zachována napříč jazyky.
- **Textové sloupce** definované v `app.catalog.tables[].text-columns`
  jsou v odpovědi nahrazeny překladem z TM (klíč: `catalogId + recordId
  + fieldName + lang`).
- **Chybějící překlad** → graceful fallback na originál (žádná chyba).
- **Tabulka mimo katalogovou konfiguraci** → pass-through bez substituce.
- **Bez `lang` parametru** → vrací originál (transparentní pass-through).

### SQL-proxy příklady

#### 1. Originální SQL aplikace, jen lokalizovaný výstup

```bash
curl -u maximo:maximo-pwd-change-me \
     -H "Content-Type: application/json" \
     -X POST "http://localhost:8080/api/v1/sql?lang=cs" \
     -d '{"query": "SELECT ITEMNUM, DESCRIPTION, LONGDESCRIPTION, CATEGORY FROM ITEM WHERE CATEGORY = '\''HYDRAULICS'\''"}'
```

Odpověď (Elasticsearch SQL kompatibilní):

```json
{
  "columns": ["ITEMNUM", "DESCRIPTION", "LONGDESCRIPTION", "CATEGORY"],
  "rows": [
    ["15240-H2500",
     "Hydraulické čerpadlo H-2500, 500 W, pro náročný provoz",
     "<p>Hydraulické čerpadlo pro náročné průmyslové aplikace…</p>",
     "HYDRAULICS"]
  ],
  "table": "ITEM",
  "language": "cs",
  "rowCount": 1,
  "translationsApplied": 2,
  "elapsedMs": 12
}
```

Poznámky:
- **`CATEGORY = 'HYDRAULICS'`** — filtr se aplikuje na anglickou hodnotu
  ve zdrojové DB (CATEGORY je sloupec mimo `text-columns`, neexistuje
  v překladu)
- **`DESCRIPTION` a `LONGDESCRIPTION`** byly přeloženy z `text-columns`
- **Pořadí sloupců, typy, počet řádků** identické s originálem

#### 2. Přes hlavičku Accept-Language (typický REST klient)

```bash
curl -u maximo:maximo-pwd-change-me \
     -H "Content-Type: application/json" \
     -H "Accept-Language: de-DE,de;q=0.9" \
     -X POST http://localhost:8080/api/v1/sql \
     -d '{"query": "SELECT ITEMNUM, DESCRIPTION FROM ITEM ORDER BY DESCRIPTION"}'
```

Vrátí překlady v němčině; `ORDER BY` se aplikuje na zdrojový text
(zachovává deterministické pořadí napříč jazyky).

#### 3. Bezpečnost: pouze SELECT

```bash
curl -u maximo:maximo-pwd-change-me \
     -H "Content-Type: application/json" \
     -X POST "http://localhost:8080/api/v1/sql?lang=cs" \
     -d '{"query": "DROP TABLE ITEM"}'
```

→ HTTP 400 `{"error": "bad_request", "message": "Only SELECT statements are allowed"}`

### Migrační vzor pro existující aplikaci

**Před** (přímý JDBC):
```java
String sql = "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE CATEGORY = ?";
PreparedStatement ps = connection.prepareStatement(sql);
ps.setString(1, "HYDRAULICS");
ResultSet rs = ps.executeQuery();
```

**Po** (HTTP přes sidecar — žádná změna SQL):
```java
String sql = "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE CATEGORY = 'HYDRAULICS'";
HttpRequest req = HttpRequest.newBuilder()
    .uri(URI.create("http://sidecar:8080/api/v1/sql?lang=" + userLang))
    .POST(BodyPublishers.ofString("{\"query\":\"" + sql + "\"}"))
    .header("Content-Type", "application/json")
    .header("Authorization", "Basic " + Base64…)
    .build();
JsonNode result = mapper.readTree(httpClient.send(req, …).body());
// result.columns + result.rows → použít stejně jako ResultSet
```

Tento vzor odpovídá **Elasticsearch `_sql` REST API** — známé enterprise
týmům — takže existující ES-SQL klienti (Logstash, Kibana, Grafana ES
plugin, JDBC bridge) fungují téměř bez úprav.

## Architektonické principy

| Princip | Realizace |
|---|---|
| **Loose Coupling** | Sidecar nikdy nepíše do zdrojové DB (read-only JDBC). |
| **Strategy Pattern** | NMT provider lze měnit přes `ai.default-provider` bez restartu kódu. |
| **Append-Only audit log** | Každý retranslate vytvoří novou `version`; historie zachována. |
| **Idempotence** | Shodný zdrojový text → překlad přeskočen (nešpiní API kvótu). |
| **Multi-tenant via catalogId** | Více katalogů (Maximo, SAP, custom) v jednom běhu, oddělené namespacem. |
| **Bezpečnost** | `POST /sync` jen pro role `ADMIN`; čtení (`GET`) i pro `MAXIMO`. |

## Přechod do produkce

1. **DB driver** — přidat do `pom.xml` runtime dependency (`postgresql`, `db2jcc`,
   `ojdbc11`, `mssql-jdbc`) a~přepnout `spring.datasource.url`.
2. **Vypnout demo init** — `CATALOG_DB_INIT_MODE=never` (nepřepisuje produkční schema).
3. **NMT provider** — `ai.default-provider=deepl` + nastavit `DEEPL_API_KEY`.
4. **Storage backend** — pro velké katalogy (>10⁶ záznamů) přepnout
   `CatalogTranslationRepository` na Elasticsearch (zachovává stejné rozhraní —
   stačí jediná `@Repository` implementace s `ElasticsearchOperations`).
5. **Credentials** — rotovat `MAXIMO_PASSWORD`/`ADMIN_PASSWORD`,
   nebo zapnout OAuth2 přes `spring.security.oauth2.resourceserver.jwt.issuer-uri`.

## Kde najít kód

```
src/main/java/cz/mendelu/auto/catalog/
├── CatalogProperties.java               # @ConfigurationProperties (config)
├── CatalogRow.java                      # entity (1 row from source DB)
├── CatalogReader.java                   # JDBC reader (metadata + rows)
├── CatalogTranslation.java              # entity for ES storage
├── CatalogTranslationRepository.java    # multilingual TM (per-key versioned)
├── CatalogTranslationService.java       # batch sync orchestrator
├── CatalogController.java               # REST: /catalogs/{id}/sync, /records, /stats
├── SqlProxyService.java                 # SQL parse + execute + substitute
└── SqlController.java                   # REST: POST /sql?lang=xx (drop-in)

src/test/java/cz/mendelu/auto/
├── CatalogTranslationServiceTest.java   # 7 e2e tests (H2 + sync + retrieval)
└── SqlProxyServiceTest.java             # 10 SQL proxy tests
```
