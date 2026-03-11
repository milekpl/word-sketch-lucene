package pl.marcinmilkowski.word_sketch.indexer.blacklab;

import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndexWriter;
import nl.inl.blacklab.index.Indexer;
import nl.inl.blacklab.index.IndexListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Indexer for CoNLL-U files using BlackLab.
 * 
 * Uses BlackLab's Indexer API with the CoNLL-U format configuration.
 */
public class BlackLabConllUIndexer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BlackLabConllUIndexer.class);

    private final BlackLabIndexWriter indexWriter;
    private final Indexer indexer;
    private final Path indexPath;
    private final AtomicLong documentCount = new AtomicLong(0);
    private final AtomicLong tokenCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    public BlackLabConllUIndexer(String indexPath, String formatName) throws IOException {
        this.indexPath = Paths.get(indexPath);
        Files.createDirectories(this.indexPath);

        try {
            this.indexWriter = BlackLab.openForWriting(this.indexPath.toFile(), true, formatName);
            this.indexer = Indexer.create(this.indexWriter, formatName);
            
            // Set up listener for progress reporting
            this.indexer.setListener(new IndexListener() {
                @Override
                public void fileStarted(String name) {
                    logger.info("Indexing: {}", name);
                }

                @Override
                public void fileDone(String name) {
                    documentCount.incrementAndGet();
                    if (documentCount.get() % 1000 == 0) {
                        logger.info("Indexed {} documents...", documentCount.get());
                    }
                }

                @Override
                public void tokensDone(int n) {
                    tokenCount.addAndGet(n);
                }

                @Override
                public boolean errorOccurred(Throwable e, String path, File f) {
                    long errors = errorCount.incrementAndGet();
                    logger.error("Indexing error in {} (error #{}, docs so far: {}, tokens so far: {}): {}",
                        path, errors, documentCount.get(), tokenCount.get(), e.getMessage());
                    logger.error("Stack trace:", e);
                    logger.warn("Continuing after error — document/token counts may be understated");
                    return true; // continue indexing
                }
            });
        } catch (Exception e) {
            throw new IOException("Failed to create index: " + e.getMessage(), e);
        }
    }

    /**
     * Index a CoNLL-U file or directory path.
     * Both file and directory inputs are supported; directory inputs are recursed automatically.
     */
    public void indexFile(String conlluPath) throws IOException {
        Path path = Paths.get(conlluPath);

        if (!Files.exists(path)) {
            throw new IOException("File not found: " + conlluPath);
        }

        // Index the file
        indexer.index(path.toFile());
    }

    @Override
    public void close() throws IOException {
        if (indexer != null) {
            try {
                indexer.close(); // commits segments and writes BlackLab field metadata
            } catch (Exception e) {
                throw new IOException("Failed to finalize index: " + e.getMessage(), e);
            }
            logger.info("Indexing complete! Documents: {}, Tokens: {}, Errors: {}",
                    documentCount.get(), tokenCount.get(), errorCount.get());
            if (errorCount.get() > 0) {
                logger.warn("Indexing completed with {} error(s) — document/token counts may be understated",
                    errorCount.get());
            }
        }
    }

    public long getDocumentCount() {
        return documentCount.get();
    }

    public long getTokenCount() {
        return tokenCount.get();
    }

    /** Returns the number of documents that failed during indexing. Non-zero means the index may be incomplete. */
    public long getErrorCount() {
        return errorCount.get();
    }
}
