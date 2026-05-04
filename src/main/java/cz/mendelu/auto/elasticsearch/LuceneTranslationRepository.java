package cz.mendelu.auto.elasticsearch;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * Translation Memory implementovaná přímo přes Apache Lucene 9.x.
 *
 * <p>Apache Lucene je open-source knihovna pro full-text a~vektorové
 * vyhledávání, na~které je postaven Elasticsearch i~Solr. Pro pilotní
 * nasazení v~rámci jednoho procesu (kap.~5 prototyp) je Lucene použito
 * <b>přímo</b> bez wrapperu Elasticsearch~--- získáváme tak shodný
 * algoritmus kNN vyhledávání ({@code VectorSimilarityFunction.COSINE},
 * HNSW graf) bez nutnosti spouštět cluster.
 *
 * <p><b>Migrace na~Elasticsearch produkční cluster:</b> rozhraní
 * {@code lookup}/{@code save} je shodné s~třídou
 * {@link TranslationRepository} (in-memory). Náhrada na~Spring Data
 * Elasticsearch s~Elasticsearch HTTP serverem znamená pouze záměnu
 * implementace tohoto repository (Strategy pattern v~Spring DI).
 *
 * <p>Aktivace přes {@code application.yml}:
 * <pre>
 *   app.cache.repository: lucene   # výchozí je in-memory
 * </pre>
 */
@Slf4j
@Repository
@Primary
@ConditionalOnProperty(name = "app.cache.repository",
                       havingValue = "lucene",
                       matchIfMissing = false)
public class LuceneTranslationRepository {

    public static final String FIELD_ID            = "id";
    public static final String FIELD_VECTOR        = "vector";
    public static final String FIELD_SOURCE_TEXT   = "sourceText";
    public static final String FIELD_TRANS_TEXT    = "translatedText";
    public static final String FIELD_SOURCE_LANG   = "sourceLang";
    public static final String FIELD_TARGET_LANG   = "targetLang";
    public static final String FIELD_PROVIDER      = "provider";
    public static final String FIELD_VERSION       = "version";
    public static final String FIELD_TIMESTAMP     = "timestamp";

    @Value("${app.cache.lucene.index-path:./tm-index}")
    private String indexPath;

    @Value("${app.cache.lucene.in-memory:false}")
    private boolean inMemory;

    private Directory directory;
    private IndexWriter writer;

    @PostConstruct
    public void init() throws IOException {
        if (inMemory) {
            directory = new ByteBuffersDirectory();
            log.info("Initialized in-memory Lucene directory");
        } else {
            Path p = Paths.get(indexPath);
            Files.createDirectories(p);
            directory = new MMapDirectory(p);
            log.info("Initialized Lucene MMapDirectory at {}", p.toAbsolutePath());
        }
        IndexWriterConfig cfg = new IndexWriterConfig();
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(directory, cfg);
        // Force commit prázdného indexu, aby DirectoryReader nehoroval
        writer.commit();
    }

    @PreDestroy
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    /**
     * Vyhledá nejvíce podobný záznam v TM dle kosinové podobnosti.
     */
    public CacheLookupResult lookup(float[] queryVector, double threshold)
            throws IOException {
        long start = System.nanoTime();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            if (reader.numDocs() == 0) {
                return new CacheLookupResult(false, 0.0, null, null,
                        elapsed(start));
            }
            IndexSearcher searcher = new IndexSearcher(reader);
            // Klíčové: KnnFloatVectorQuery — totéž, co Elasticsearch
            // používá pod kapotou pro {"knn": {...}} dotaz.
            KnnFloatVectorQuery knn = new KnnFloatVectorQuery(
                    FIELD_VECTOR, queryVector, 1);
            TopDocs hits = searcher.search(knn, 1);
            if (hits.scoreDocs.length == 0) {
                return new CacheLookupResult(false, 0.0, null, null,
                        elapsed(start));
            }
            ScoreDoc top = hits.scoreDocs[0];
            // Lucene normalizuje score do (0,1] pro cosine (½(1+cos))
            // Přepočet na čisté cosine ∈ [-1, 1]: cos = 2*score - 1
            double cosine = 2.0 * top.score - 1.0;
            org.apache.lucene.document.Document doc =
                    searcher.storedFields().document(top.doc);
            boolean hit = cosine >= threshold;
            return new CacheLookupResult(
                    hit,
                    cosine,
                    hit ? doc.get(FIELD_TRANS_TEXT) : null,
                    hit ? doc.get(FIELD_ID) : null,
                    elapsed(start)
            );
        }
    }

    /**
     * Append-only zápis nového záznamu do TM.
     */
    public synchronized String save(
            String sourceText,
            String translatedText,
            float[] sourceVector,
            String sourceLang,
            String targetLang,
            String provider
    ) throws IOException {
        String id = UUID.randomUUID().toString();
        org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
        doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
        doc.add(new KnnFloatVectorField(FIELD_VECTOR, sourceVector,
                org.apache.lucene.index.VectorSimilarityFunction.COSINE));
        doc.add(new StoredField(FIELD_SOURCE_TEXT,  sourceText));
        doc.add(new StoredField(FIELD_TRANS_TEXT,   translatedText));
        doc.add(new StoredField(FIELD_SOURCE_LANG,  sourceLang));
        doc.add(new StoredField(FIELD_TARGET_LANG,  targetLang));
        doc.add(new StoredField(FIELD_PROVIDER,     provider));
        doc.add(new StoredField(FIELD_VERSION,      1));
        doc.add(new StoredField(FIELD_TIMESTAMP,    Instant.now().toString()));
        writer.addDocument(doc);
        writer.commit();
        return id;
    }

    /** Počet záznamů v TM. */
    public int count() throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    /** Vyprázdnění TM (pouze testy). */
    public synchronized void clear() throws IOException {
        writer.deleteAll();
        writer.commit();
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
