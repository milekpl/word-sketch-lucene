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
                    logger.error("Indexing error in {}: {}", path, e.getMessage());
                    if (e != null) {
                        logger.error("Stack trace:", e);
                    }
                    return true; // continue indexing
                }
            });
        } catch (Exception e) {
            throw new IOException("Failed to create index: " + e.getMessage(), e);
        }
    }

    /**
     * Index a single CoNLL-U file.
     */
    public void indexFile(String conlluPath) throws IOException {
        Path path = Paths.get(conlluPath);

        if (!Files.exists(path)) {
            throw new IOException("File not found: " + conlluPath);
        }

        // Index the file
        indexer.index(path.toFile());
    }

    /**
     * Index all CoNLL-U files in a directory.
     */
    public void indexDirectory(String dirPath, String pattern) throws IOException {
        Path dir = Paths.get(dirPath);

        if (!Files.isDirectory(dir)) {
            throw new IOException("Directory not found: " + dirPath);
        }

        logger.info("Indexing directory: {}", dirPath);

        try (var stream = Files.newDirectoryStream(dir, pattern)) {
            for (Path file : stream) {
                indexFile(file.toString());
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (indexer != null) {
            try {
                indexer.close(); // commits segments and writes BlackLab field metadata
            } catch (Exception e) {
                throw new IOException("Failed to finalize index: " + e.getMessage(), e);
            }
            logger.info("Indexing complete! Documents: {}, Tokens: {}, Index: {}",
                    documentCount.get(), tokenCount.get(), indexPath);
        }
    }

    public long getDocumentCount() {
        return documentCount.get();
    }

    public long getTokenCount() {
        return tokenCount.get();
    }
}
