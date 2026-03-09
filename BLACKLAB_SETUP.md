# BlackLab 4.0.0 Integration

## Quick Start

### 1. Install BlackLab JARs to Local Maven Repository

**Linux/Mac:**
```bash
./install-blacklab.sh
```

**Windows (PowerShell):**
```powershell
.\install-blacklab.ps1
```

This downloads BlackLab 4.0.0 from GitHub releases and installs it to your local Maven repository (`~/.m2/repository`).

### 2. Build the Project

```bash
mvn clean package
```

### 3. Tag Corpus with Stanza (GPU-enabled)

```bash
# Install Stanza with CUDA support
pip install -r requirements-stanza-gpu.txt

# Download Stanza models
python tag_with_stanza.py --download --lang en

# Tag corpus (uses GPU automatically)
python tag_with_stanza.py -i corpus.txt -o corpus.conllu --lang en
```

### 4. Index with BlackLab

```bash
java -jar target/concept-sketch-1.5.0-shaded.jar blacklab-index \
  --input corpus.conllu \
  --output data/index/
```

### 5. Query

```bash
# Find adjectival modifiers of "theory"
java -jar target/concept-sketch-1.5.0-shaded.jar blacklab-query \
  --index data/index/ \
  --lemma theory \
  --deprel amod
```

### 6. Start API Server

```bash
java -jar target/concept-sketch-1.5.0-shaded.jar server \
  --index data/index/ \
  --port 8080
```

---

## Manual Installation (Alternative)

If the install script fails, you can manually install:

```bash
# Download
wget https://github.com/instituutnederlandsetaal/BlackLab/releases/download/v4.0.0/blacklab-4.0.0-jar.zip

# Extract
unzip blacklab-4.0.0-jar.zip

# Install to Maven
mvn install:install-file \
  -Dfile=blacklab-core-4.0.0.jar \
  -DgroupId=nl.inl.blacklab \
  -DartifactId=blacklab-core \
  -Dversion=4.0.0 \
  -Dpackaging=jar
```

---

## What's Included in BlackLab 4.0.0

- **CoNLL-U dependency indexing** - Native support for deprel relations
- **CQL with dependency arrows** - `[lemma="X"] <amod []`
- **Server-side grouping** - Fast frequency aggregation
- **Lucene 8.11.1** - Compatible with our Java 17 target

---

## Troubleshooting

### "Could not find artifact nl.inl.blacklab:blacklab-core:jar:4.0.0"

Run the install script first:
```bash
./install-blacklab.sh
```

Verify installation:
```bash
ls ~/.m2/repository/nl/inl/blacklab/blacklab-core/4.0.0/
```

Should contain:
- `blacklab-core-4.0.0.jar`
- `blacklab-core-4.0.0.pom`

### Build fails with Lucene version conflict

BlackLab 4.0.0 uses Lucene 8.11.1. Make sure your pom.xml matches:
```xml
<lucene.version>8.11.1</lucene.version>
```

### Stanza runs on CPU instead of GPU

Check CUDA availability:
```bash
python -c "import torch; print(torch.cuda.is_available())"
```

If False, reinstall PyTorch with CUDA:
```bash
pip uninstall torch
pip install torch --index-url https://download.pytorch.org/whl/cu118
```

---

## File Structure

```
concept-sketch/
├── install-blacklab.sh          # Linux/Mac installer
├── install-blacklab.ps1         # Windows installer
├── tag_with_stanza.py           # GPU-enabled corpus tagger
├── requirements-stanza-gpu.txt  # Stanza + CUDA dependencies
├── pom.xml                      # Maven config (BlackLab 4.0.0)
├── src/main/java/.../
│   ├── Main.java                        # Entry point
│   ├── query/
│   │   └── BlackLabQueryExecutor.java   # BlackLab queries
│   └── indexer/blacklab/
│       └── BlackLabConllUIndexer.java   # CoNLL-U indexer
└── webapp/                      # Web UI (unchanged)
```

---

## Next Steps

1. **Test with small corpus** (10K sentences)
2. **Verify dependency queries work** (`amod`, `nsubj`, `obj`)
3. **Compare results** vs. old implementation
4. **Migrate semantic exploration** features
5. **Full corpus indexing**

---

## References

- [BlackLab v4.0.0 Release](https://github.com/instituutnederlandsetaal/BlackLab/releases/tag/v4.0.0)
- [BlackLab Documentation](https://blacklab.ivdnt.org/)
- [Stanza GPU Setup](STANZA_GPU.md)
