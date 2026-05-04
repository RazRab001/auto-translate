# Dva režimy služby

Sidecar middleware podporuje **dva nezávislé režimy** podle toho, jak je
existující aplikace integrovaná. Lze je použít kombinovaně nebo izolovaně —
oba sdílejí společné jádro (NMT konektory, cache, retry, circuit breaker).

```
┌────────────────────────────────────────────────────────────────────┐
│                         AUTO-TRANSLATE SIDECAR                     │
├──────────────────────────────────────┬─────────────────────────────┤
│                                      │                             │
│  MODE 1: Catalog (full DB pipeline)  │  MODE 2: Simple (text/doc)  │
│                                      │                             │
│  ┌────────┐    ┌──────────┐    ┌──┐  │   ┌──────────────┐          │
│  │ Source │───►│ Catalog  │───►│ES│  │   │ Plain text   │ ───►     │
│  │   DB   │    │ Translat.│    │TM│  │   │ /translate/  │ NMT API  │
│  │ (JDBC) │    │ Service  │    └─┬┘  │   │   sync       │ ───►     │
│  └────────┘    └──────────┘      │   │   └──────────────┘          │
│       ▲                          │   │                             │
│       │ same SQL                 ▼   │   ┌──────────────┐          │
│  ┌────────────┐         ┌────────────┐│  │ PDF / TXT    │ ───►     │
│  │ App / BI   │ ───────►│ SQL proxy  ││  │ /translate/  │ NMT API  │
│  │ tool       │         │ (substitut.│││ │   document   │ ───►     │
│  └────────────┘         │  by lang)  │││ │ (multipart)  │          │
│                         └────────────┘│  └──────────────┘          │
│                                      │                             │
│  STATEFUL: TM ukládá překlady,       │  STATELESS (cache jen pro   │
│  retrieval podle (catalogId,         │  deduplikaci); jednorázový  │
│  recordId, fieldName, lang)          │  request → response         │
│                                      │                             │
└──────────────────────────────────────┴─────────────────────────────┘
```

## Kdy který režim

| Situace | Mode 1 | Mode 2 |
|---|---|---|
| Maximo / SAP / podniková DB má tisíce katalogových položek | ✅ | — |
| Existující app posílá SQL dotazy přímo na DB | ✅ (SQL proxy) | — |
| Potřebuji ten samý SELECT, jen lokalizovaný | ✅ (SQL proxy) | — |
| Jednorázový překlad textu z UI | — | ✅ |
| Uživatel uploadne PDF přílohu, chce ji v jiném jazyce | — | ✅ |
| API klient pošle JSON `{text, targetLang}` | — | ✅ |
| Stejnou položku potřebuji v 5 jazycích | ✅ (jeden sync) | ❌ (5 requestů) |
| Auditní stopa (kdo, kdy, co bylo přeloženo) | ✅ (versioned TM) | ❌ |
| Lze měnit zdrojovou DB nezávisle na aplikaci | ✅ | n/a |
| Klientská app nesmí dostat surová zdrojová data | ✅ | n/a |

---

## Mode 1: Full DB translation + SQL proxy

**Use case:** existující aplikace přistupuje k relační DB (Maximo / Db2 /
PostgreSQL / Oracle) přes JDBC nebo SQL queries. Chcete, aby výsledky byly
lokalizované do více jazyků **bez modifikace aplikace ani DB schématu**.

### Workflow

1. **Sync** — sidecar přečte tabulky a~přeloží všechna textová pole do
   všech nakonfigurovaných cílových jazyků (DeepL/Google/OpenAI).
   Idempotentní: opakovaný sync přeskočí nezměněná pole.
2. **Storage** — překlady uloženy v ES-TM s~klíčem `(catalogId, recordId,
   fieldName, targetLang)`, append-only versioning pro audit.
3. **Retrieval** — dvě možnosti:
   - **Strukturovaný** (`GET /catalogs/{id}/records?lang=cs`) — pro nové
     klienty, kteří umí JSON
   - **SQL proxy** (`POST /sql?lang=cs` s~originálním SELECT) — drop-in
     migrace pro existující SQL klienty bez změny dotazů

### Endpointy

| Metoda | URL | Účel |
|---|---|---|
| `GET`  | `/api/v1/catalogs/{id}/metadata` | Schéma zdrojové DB |
| `POST` | `/api/v1/catalogs/{id}/sync` | Spustit překlad celého katalogu |
| `POST` | `/api/v1/catalogs/{id}/sync/async` | Asynchronně (HTTP 202) |
| `GET`  | `/api/v1/catalogs/{id}/records?lang=cs` | Celý katalog v 1 jazyce |
| `GET`  | `/api/v1/catalogs/{id}/records/{recordId}` | 1 záznam v N jazycích |
| `GET`  | `/api/v1/catalogs/{id}/stats` | Souhrn (recs, langs) |
| `POST` | `/api/v1/sql?lang=cs` | **SQL proxy** (drop-in pro existující apps) |

### Příklad: drop-in migrace existující aplikace

**Před:**
```java
// Existující kód přímo k DB
String sql = "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE CATEGORY = ?";
PreparedStatement ps = jdbcConnection.prepareStatement(sql);
ps.setString(1, "HYDRAULICS");
ResultSet rs = ps.executeQuery();
```

**Po (SQL proxy — žádná změna SQL):**
```java
String sql = "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE CATEGORY = 'HYDRAULICS'";
HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
    .uri(URI.create("http://sidecar:8080/api/v1/sql?lang=" + userLanguage))
    .header("Content-Type", "application/json")
    .header("Authorization", "Basic " + creds)
    .POST(BodyPublishers.ofString(
        mapper.writeValueAsString(Map.of("query", sql))))
    .build(), BodyHandlers.ofString());
JsonNode result = mapper.readTree(resp.body());
// result.columns + result.rows ─── použít stejně jako ResultSet
```

→ aplikace dostane ty samé řádky, ale `DESCRIPTION` je v zvoleném jazyce.

### Konfigurace

```yaml
spring:
  datasource:
    url: jdbc:postgresql://maximo-db:5432/MAXIMO
    username: ${DB_USER}
    password: ${DB_PASSWORD}

app:
  catalog:
    id: maximo-items
    source-lang: en
    target-langs: [cs, de, sk, pl]
    tables:
      - name: ITEM
        id-column: ITEMNUM
        text-columns: [DESCRIPTION, LONGDESCRIPTION]
```

Detail: viz [`CATALOG_SERVICE.md`](./CATALOG_SERVICE.md).

---

## Mode 2: Simple translation (text + document)

**Use case:** klientská aplikace má **jednorázový text** nebo **soubor**
a~potřebuje překlad. Bez DB, bez schématu, bez konfigurace katalogu.

### Workflow

1. Klient pošle text (JSON) nebo dokument (multipart/form-data).
2. Sidecar přeloží přes nakonfigurovaný NMT provider.
3. Vrátí překlad. Hotovo. (TM cache se přesto použije pro deduplikaci,
   ale není nutná konfigurace.)

### Endpointy

| Metoda | URL | Vstup | Výstup |
|---|---|---|---|
| `POST` | `/api/v1/translate/sync` | JSON `{text, sourceLang, targetLang}` | JSON `{translation, provider, latencyMs}` |
| `POST` | `/api/v1/translate` | Same + idempotency-key header | HTTP 202 + jobId, pak `GET /jobs/{id}` |
| `POST` | `/api/v1/translate/document` | multipart `file` (PDF/TXT/MD) | JSON s~překladem celého textu souboru |

### Příklady

#### Plain text

```bash
curl -u maximo:maximo-pwd-change-me \
     -H "Content-Type: application/json" \
     -X POST http://localhost:8080/api/v1/translate/sync \
     -d '{
       "sourceText": "Hydraulic Pump 500W",
       "sourceLang": "en",
       "targetLang": "cs"
     }'
```

→ `{"text": "Hydraulické čerpadlo 500 W", "provider": "deepl", "latencyMs": 254, "attempts": 1}`

#### PDF dokument

```bash
curl -u maximo:maximo-pwd-change-me \
     -F "file=@manual.pdf" \
     -F "sourceLang=en" \
     -F "targetLang=cs" \
     -X POST http://localhost:8080/api/v1/translate/document
```

→
```json
{
  "fileName": "manual.pdf",
  "fileType": "pdf",
  "fileSizeBytes": 458120,
  "extractedChars": 12340,
  "sourceLang": "en",
  "targetLang": "cs",
  "translation": "Návod k obsluze hydraulického čerpadla…",
  "provider": "deepl",
  "latencyMs": 1820,
  "attempts": 1
}
```

#### Asynchronně (pro velké texty / dlouhý překlad)

```bash
# 1. Submit
curl -u maximo:... -H "Content-Type: application/json" -X POST \
     -H "Idempotency-Key: my-doc-001" \
     http://localhost:8080/api/v1/translate \
     -d '{"sourceText":"…","sourceLang":"en","targetLang":"cs"}'
# → HTTP 202 {"jobId":"job-abc","status":"PROCESSING","retryAfterMs":1500}

# 2. Poll
curl -u maximo:... http://localhost:8080/api/v1/jobs/job-abc
# → {"jobId":"job-abc","status":"DONE","translation":"…","provider":"deepl","cacheHit":false,"latencyMs":287}
```

### Limity Mode 2

- **Max upload size**: 10 MB (konfigurovatelné přes `spring.servlet.multipart.max-file-size`)
- **PDF**: pouze textová vrstva (skenované PDF bez OCR vyžaduje rozšíření)
- **Žádný retrieval**: vrácený překlad nelze později vyžádat — sidecar v tomto režimu neukládá výstup pod žádným klíčem (cache funguje jen jako deduplikace přes embedding podobnost)

---

## Společná jádra obou režimů

Oba režimy sdílejí:
- **NMT konektory** — `TranslationProvider` (DeepL / Google / OpenAI / mock) přepínané přes `ai.default-provider`
- **HTML sanitizer** — `<br>`/`<b>`/`<p>` se zachovají při překladu
- **Sémantická TM cache** — embedding-based deduplikace
- **Retry + Circuit Breaker** — Spring Retry (3 pokusy, exp. backoff) + Resilience4j (CB při ≥50% failures)
- **Audit logging** — strukturované JSON logy s~jobId, providerem, latencí
- **Security** — HTTP Basic + volitelně OAuth2 JWT, role MAXIMO/ADMIN

## Kdy použít kombinaci

V praxi často oba režimy dohromady:
1. **Mode 1** — noční sync překladů katalogu (Maximo ITEM, ITEM_SPECIFICATION) →
   uživatel vidí lokalizovaný UI okamžitě (čerpáno z TM bez NMT API call).
2. **Mode 2** — když uživatel nahraje **novou přílohu PDF** k existující
   položce, nebo přidá poznámku v textu → real-time překlad přes
   `/translate/document` nebo `/translate/sync`.

Tím se kombinuje:
- Bulk amortizovaný náklad (Mode 1, jeden sync, mnoho jazyků)
- Ad-hoc real-time použitelnost (Mode 2, individuální požadavky)
