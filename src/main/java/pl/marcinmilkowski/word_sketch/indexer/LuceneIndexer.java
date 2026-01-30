package pl.marcinmilkowski.word_sketch.indexer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lucene indexer for creating word sketch indexes.
 * This class handles the creation of Lucene indexes with the appropriate field configuration.
 * Supports concurrent indexing with ThreadPoolExecutor for improved performance.
 */
public class LuceneIndexer {

    private static final Logger logger = LoggerFactory.getLogger(LuceneIndexer.class);

    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_POSITION = "position";
    private static final String FIELD_WORD = "word";
    private static final String FIELD_LEMMA = "lemma";
    private static final String FIELD_TAG = "tag";
    private static final String FIELD_POS_GROUP = "pos_group";
    private static final String FIELD_SENTENCE = "sentence";
    private static final String FIELD_START_OFFSET = "start_offset";
    private static final String FIELD_END_OFFSET = "end_offset";

    private final Directory directory;
    private final IndexWriter writer;

    // Thread pool for concurrent indexing
    private final ThreadPoolExecutor executor;
    private final int queueSize = 10000;
    private int pendingDocs = 0;
    private final Object flushLock = new Object();

    public LuceneIndexer(String indexPath) throws IOException {
        this(indexPath, 1); // Default to 1 thread (sequential)
    }

    public LuceneIndexer(String indexPath, int numThreads) throws IOException {
        Path path = Paths.get(indexPath);

        // Create parent directories if needed
        Files.createDirectories(path);

        // Use MMapDirectory for better I/O performance on large indexes
        this.directory = MMapDirectory.open(path);

        Analyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(256.0);  // 256MB buffer

        this.writer = new IndexWriter(directory, config);

        // Create thread pool for concurrent indexing
        if (numThreads > 1) {
            this.executor = new ThreadPoolExecutor(
                numThreads, numThreads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queueSize),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            logger.info("Lucene indexer initialized with {} parallel threads", numThreads);
        } else {
            this.executor = null;
            logger.info("Lucene indexer initialized (sequential mode)");
        }
    }

    /**
     * Adds a word occurrence to the index (thread-safe).
     */
    public void addWord(int docId, int position, String word, String lemma,
                       String tag, String posGroup, String sentence,
                       int startOffset, int endOffset) throws IOException {
        Document doc = createDocument(docId, position, word, lemma, tag, posGroup, sentence,
                                     startOffset, endOffset);

        if (executor != null) {
            // Concurrent mode - submit to thread pool
            synchronized (flushLock) {
                pendingDocs++;
            }
            executor.submit(() -> {
                try {
                    writer.addDocument(doc);
                } catch (IOException e) {
                    logger.error("Error adding document", e);
                } finally {
                    synchronized (flushLock) {
                        pendingDocs--;
                        if (pendingDocs >= queueSize) {
                            flushLock.notify();
                        }
                    }
                }
            });
        } else {
            // Sequential mode - add directly
            writer.addDocument(doc);
        }
    }

    /**
     * Flushes all pending documents (concurrent mode only).
     */
    public void flushPending() throws IOException {
        if (executor != null) {
            synchronized (flushLock) {
                while (pendingDocs > 0) {
                    try {
                        flushLock.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            writer.commit();
        }
    }

    private Document createDocument(int docId, int position, String word, String lemma,
                                    String tag, String posGroup, String sentence,
                                    int startOffset, int endOffset) {
        Document doc = new Document();

        // Stored fields for display
        doc.add(new StoredField(FIELD_DOC_ID, docId));
        doc.add(new StoredField(FIELD_POSITION, position));
        doc.add(new StoredField(FIELD_WORD, word));
        doc.add(new StoredField(FIELD_LEMMA, lemma));
        doc.add(new StoredField(FIELD_TAG, tag));
        doc.add(new StoredField(FIELD_POS_GROUP, posGroup));
        doc.add(new StoredField(FIELD_SENTENCE, sentence));
        doc.add(new StoredField(FIELD_START_OFFSET, startOffset));
        doc.add(new StoredField(FIELD_END_OFFSET, endOffset));

        // Indexed fields for searching
        // Note: StandardAnalyzer lowercases text, so we lowercase tags for matching
        doc.add(new TextField(FIELD_LEMMA, (lemma != null ? lemma : "").toLowerCase(), Field.Store.NO));
        doc.add(new TextField(FIELD_TAG, (tag != null ? tag : "").toLowerCase(), Field.Store.NO));
        doc.add(new TextField(FIELD_POS_GROUP, (posGroup != null ? posGroup : "").toLowerCase(), Field.Store.NO));

        // Index doc_id as StringField for sentence-level queries
        doc.add(new StringField(FIELD_DOC_ID, String.valueOf(docId), Field.Store.YES));

        return doc;
    }

    /**
     * Commits the changes to the index.
     */
    public void commit() throws IOException {
        if (executor != null) {
            // Wait for pending documents
            flushPending();
        }
        writer.commit();
        logger.info("Index committed.");
    }

    /**
     * Optimizes the index (optional but recommended for large indexes).
     */
    public void optimize() throws IOException {
        if (executor != null) {
            flushPending();
            executor.shutdown();
            try {
                executor.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writer.forceMerge(1);
        logger.info("Index optimized.");
    }

    /**
     * Closes the index writer and directory.
     */
    public void close() throws IOException {
        if (executor != null) {
            flushPending();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (writer != null) {
            writer.close();
        }
        if (directory != null) {
            directory.close();
        }
        logger.info("Indexer closed.");
    }

    /**
     * Gets the number of documents in the index.
     */
    public long getDocumentCount() throws IOException {
        return writer.getDocStats().numDocs;
    }
}
