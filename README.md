# Auto-Translate Sidecar Middleware

Sidecar middleware pro IBM Maximo Application Suite (a libovolnou jinou enterprise aplikaci s relační databází), který poskytuje **sjednocený překlad katalogových dat a textových dokumentů** přes neuronové překladové služby (DeepL, Google Cloud Translation, OpenAI GPT-4o-mini) doplněný o **sémantickou Translation Memory cache** postavenou nad Apache Lucene kNN.

Java 17, Spring Boot 3.2, Maven.

## Architektura

Sidecar pattern — služba běží jako samostatný proces (typicky kontejner) **vedle** existující aplikace. Klientská aplikace (Maximo) volá sidecar přes REST nebo přes SQL-proxy endpoint a žádné změny v jejím vlastním kódu nejsou potřeba.

```
┌──────────────┐    REST / SQL    ┌──────────────────────┐
│   Maximo     │ ───────────────► │  Auto-Translate      │ ───► DeepL / OpenAI / Google
│  (klient)    │                  │  Sidecar             │
└──────────────┘                  │                      │ ───► Lucene TM (kNN cache)
       │                          │                      │
       │  JDBC                    │                      │ ───► Maximo / katalogová DB
       └──────────────────────────┴──────────────────────┘
```

## Dva režimy

### Mode 1 — překladový pipeline pro relační DB

Sidecar přečte konfigurované tabulky ze zdrojové databáze, pošle textové sloupce do NMT poskytovatele, výsledek uloží do interního úložiště a vystaví je přes REST. Volitelně lze pak posílat přímo SQL přes proxy endpoint, který transparentně nahradí texty cílovým jazykem.

- `POST /api/v1/catalogs/{id}/sync` — spustí sync (sync nebo async varianta)
- `GET  /api/v1/catalogs/{id}/records?lang=cs` — vrátí katalog v daném jazyce
- `GET  /api/v1/catalogs/{id}/metadata` — schéma + dostupné jazyky
- `POST /api/v1/sql/execute` — drop-in transparentní SQL proxy

### Mode 2 — překlad textu nebo dokumentu na vyžádání

Klient pošle text nebo nahraje PDF/DOCX, dostane zpět překlad. Žádná persistence (kromě cache hits).

- `POST /api/v1/translate/sync` — synchronní text → text
- `POST /api/v1/translate` — asynchronní (vrátí `jobId`)
- `GET  /api/v1/jobs/{jobId}` — stav úlohy
- `POST /api/v1/translate/document` — multipart upload souboru

Detailní popis obou režimů: viz [`MODES.md`](./MODES.md). Detail Mode 1: [`CATALOG_SERVICE.md`](./CATALOG_SERVICE.md).

## Quick start

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Run (mock provider — žádný API klíč není potřeba)

```bash
java -jar target/auto-translate-sidecar-1.0.0.jar
```

Sidecar nastartuje na `http://localhost:8080`. Při startu se automaticky vytvoří H2 demo databáze s tabulkou `ITEM` a spustí se demo překlad katalogu (`CatalogDemoPreloader`), takže Swagger UI má rovnou hotová data.

### 3. Otevři v prohlížeči

| URL | Co tam je |
|---|---|
| `http://localhost:8080/console` | Web konzole — metadata, jazyky, stav DB, obsah TM cache |
| `http://localhost:8080/swagger-ui.html` | Swagger UI s "Try it out" pro všechny endpointy |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3 specifikace (JSON) |

### 4. Přepnutí na reálný NMT provider

```bash
export DEEPL_API_KEY=...
export OPENAI_API_KEY=...
export GOOGLE_API_KEY=...

java -jar target/auto-translate-sidecar-1.0.0.jar \
     --ai.default-provider=deepl
```

## Konfigurace (env proměnné)

| Proměnná | Default | Popis |
|---|---|---|
| `CATALOG_DB_URL` | H2 in-memory | JDBC URL zdrojové DB |
| `CATALOG_DB_USER` / `CATALOG_DB_PASSWORD` | `sa` / prázdné | DB credentials |
| `CATALOG_DB_DRIVER` | `org.h2.Driver` | JDBC driver class |
| `CATALOG_DB_INIT_MODE` | `always` | `always` / `never` (produkce) |
| `DEEPL_API_KEY` | prázdné | DeepL API klíč |
| `OPENAI_API_KEY` | prázdné | OpenAI API klíč |
| `GOOGLE_API_KEY` | prázdné | Google Cloud Translation klíč |
| `MAXIMO_USERNAME` / `MAXIMO_PASSWORD` | placeholder | HTTP Basic credentials pro klienta |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | placeholder | HTTP Basic credentials pro admin role |
| `DEMO_PRELOAD` | `true` | Vypni v produkci na `false` |

Hesla `*-pwd-change-me` v `application.yml` jsou jen vývojové placeholders — produkčně přesunout do Vault / K8s Secrets.

## Struktura projektu

```
service/
├── pom.xml                            # Maven config (Spring Boot 3.2.12, Java 17)
├── README.md                          # tento soubor
├── MODES.md                           # detail Mode 1 vs Mode 2
├── CATALOG_SERVICE.md                 # detail katalogového pipeline (Mode 1)
├── .gitignore
└── src/
    ├── main/
    │   ├── java/cz/mendelu/auto/
    │   │   ├── AutoTranslateApplication.java   # Spring Boot main class
    │   │   │
    │   │   ├── api/                            # REST kontroléry (Mode 2 + obecné)
    │   │   │   ├── TranslationController.java       # /api/v1/translate, /jobs, /health
    │   │   │   ├── DocumentTranslationController.java  # /api/v1/translate/document
    │   │   │   ├── ConsoleController.java           # / a /console (web UI)
    │   │   │   └── dto/                             # request/response DTO
    │   │   │
    │   │   ├── catalog/                        # Mode 1 (DB pipeline + SQL proxy)
    │   │   │   ├── CatalogController.java           # /api/v1/catalogs/{id}/...
    │   │   │   ├── CatalogProperties.java           # @ConfigurationProperties
    │   │   │   ├── CatalogReader.java               # JDBC čtení tabulek
    │   │   │   ├── CatalogTranslationService.java   # překladový orchestrátor
    │   │   │   ├── CatalogTranslationRepository.java
    │   │   │   ├── CatalogTranslation.java          # entity
    │   │   │   ├── CatalogRow.java
    │   │   │   ├── CatalogDemoPreloader.java        # auto-sync na startu
    │   │   │   ├── SqlController.java               # /api/v1/sql/execute
    │   │   │   └── SqlProxyService.java             # JSqlParser-based proxy
    │   │   │
    │   │   ├── connectors/                     # NMT poskytovatelé
    │   │   │   ├── TranslationProvider.java         # společné rozhraní
    │   │   │   ├── DeepLConnector.java
    │   │   │   ├── GoogleConnector.java
    │   │   │   ├── OpenAIConnector.java             # GPT-4o-mini
    │   │   │   ├── MockConnector.java               # offline / testy
    │   │   │   └── exceptions/
    │   │   │       └── TransientProviderException.java   # spouští retry
    │   │   │
    │   │   ├── elasticsearch/                  # Translation Memory (Apache Lucene)
    │   │   │   ├── TranslationRepository.java       # rozhraní
    │   │   │   ├── LuceneTranslationRepository.java # kNN přes Lucene 9.10
    │   │   │   ├── InMemoryTranslationRepository.java # alternativa pro testy
    │   │   │   ├── TranslationRecord.java
    │   │   │   └── VectorService.java               # embedding (OpenAI / mpnet / hash)
    │   │   │
    │   │   ├── service/                        # business logika
    │   │   │   ├── TranslationOrchestrator.java     # cache → provider → store
    │   │   │   ├── JobRegistry.java                 # async job tracking (Caffeine)
    │   │   │   ├── HtmlSanitizer.java               # ochrana HTML při překladu
    │   │   │   ├── TextNormalizer.java
    │   │   │   └── MpnetEmbedder.java               # ONNX Runtime + HF tokenizer
    │   │   │
    │   │   ├── storage/
    │   │   │   └── PdfTextExtractor.java            # PDFBox extrakce
    │   │   │
    │   │   ├── bench/                          # vyhodnocovací nástroje
    │   │   │   ├── OmegaTFuzzyMatcher.java          # baseline pro porovnání
    │   │   │   ├── CpvBenchmark.java                # CPV klasifikační benchmark
    │   │   │   └── GlossaryExpander.java
    │   │   │
    │   │   └── config/
    │   │       ├── SecurityConfig.java              # HTTP Basic + role
    │   │       ├── AsyncConfig.java                 # ThreadPoolTaskExecutor
    │   │       └── OpenApiConfig.java               # Swagger UI metadata
    │   │
    │   └── resources/
    │       ├── application.yml                # hlavní konfigurace
    │       ├── schema.sql                     # H2 demo schéma
    │       ├── data.sql                       # H2 demo data
    │       ├── logback-spring.xml             # JSON logging
    │       └── static/
    │           └── console.html               # web konzole (vanilla HTML/CSS/JS)
    │
    └── test/
        ├── java/cz/mendelu/auto/              # 17 testových tříd, ~100 testů
        │   ├── api/                                  # MockMvc API testy
        │   ├── catalog/                              # Mode 1 + SQL proxy testy
        │   ├── connectors/                           # NMT konektor mock testy
        │   ├── elasticsearch/                        # Lucene kNN testy
        │   ├── service/                              # orchestrátor testy
        │   └── storage/                              # PDF extraktor testy
        └── resources/
            └── application.yml                # test profil (mock provider)
```

## Klíčové komponenty

### Translation Memory (sémantická cache)

Před každým voláním NMT poskytovatele orchestrátor spočítá embedding vstupního textu a hledá v Lucene indexu nejbližší souseda kosinovou podobností. Pokud podobnost ≥ `app.cache.similarity-threshold` (default 0,82), vrátí cached překlad a NMT volání se přeskočí.

- **Embedding provider:** `openai` (text-embedding-3-small, 1536d) | `mpnet` (paraphrase-multilingual-mpnet-base-v2 přes ONNX Runtime, 768d) | `hash` (jen offline testy, není sémantický)
- **Repository:** Apache Lucene 9.10 `KnnFloatVectorQuery` (HNSW), MMapDirectory pro persistenci

### Resilience

- **Spring Retry** — 3 pokusy s exponenciálním backoffem (1 s → 2 s → 4 s) na `TransientProviderException` (HTTP 5xx, 429, timeout)
- **Resilience4j Circuit Breaker** — pro každý NMT provider zvlášť (`deepl`, `openai`, `google`); otevírá se při ≥50 % failure rate v okně 20 volání

### Bezpečnost

- **HTTP Basic** + role (`MAXIMO`, `ADMIN`)
- **OAuth2 Resource Server** (JWT) — připraveno, aktivuje se přidáním `spring.security.oauth2.resourceserver.jwt.issuer-uri`
- **HTML sanitizer** chrání `<br>`, `<b>`, `<p>` před narušením v překladu

### Observabilita

- **Strukturované JSON logy** přes Logback + logstash-encoder (v každé položce `jobId`, `provider`, `latencyMs`, `cacheHit`)
- **Swagger UI** (`/swagger-ui.html`) s "Try it out" tlačítky pro všechny endpointy

## Testy

```bash
mvn test
```

~100 testů: unit (parsery, sanitizer, embedder), integrační (orchestrátor + Lucene + mock provider) i API (MockMvc s ověřením auth a content-type).

## Závislosti (vrchní úroveň)

| Dependency | Verze | Účel |
|---|---|---|
| spring-boot-starter-web/webflux | 3.2.12 | REST + WebClient |
| spring-boot-starter-jdbc | 3.2.12 | čtení katalogu |
| spring-boot-starter-security | 3.2.12 | HTTP Basic + role |
| spring-boot-starter-oauth2-resource-server | 3.2.12 | volitelný JWT |
| spring-retry + spring-aspects | — | retry/backoff |
| resilience4j-spring-boot3 | 2.2.0 | Circuit Breaker |
| caffeine | 3.1.8 | JobRegistry TTL cache |
| lucene-core | 9.10.0 | kNN vyhledávání |
| jsqlparser | 4.9 | SQL proxy parsing |
| pdfbox | 3.0.2 | extrakce textu z PDF |
| onnxruntime | 1.18.0 | lokální mpnet embedding |
| ai.djl.huggingface tokenizers | 0.27.0 | BPE/WordPiece |
| h2 | 2.2.224 | demo / testy |
| postgresql | 42.7.3 | optional, runtime |
| logstash-logback-encoder | 7.4 | JSON logging |
| springdoc-openapi-starter-webmvc-ui | 2.3.0 | Swagger UI |

## Licence a původ

Bachelor thesis project, Mendel University in Brno, 2026.
