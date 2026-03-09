#!/bin/bash
# Corpus Indexing Script for ConceptSketch
# Indexes line-segmented text files (one sentence per line) or CoNLL-U files
#
# Usage:
#   ./index_corpus.sh <input_file> <output_dir> [--tagger simple|udpipe] [--language en|pl|de|...] [--batch N]
#
# Examples:
#   # Index line-segmented English text using UDPipe (recommended)
#   ./index_corpus.sh sentences.txt data/index --tagger udpipe --language en
#
#   # Index using simple rule-based tagger (no UDPipe needed)
#   ./index_corpus.sh sentences.txt data/index --tagger simple
#
#   # Index pre-tagged CoNLL-U file (fastest)
#   ./index_corpus.sh corpus.conllu data/index --format conllu
#
#   # Index with custom batch size
#   ./index_corpus.sh sentences.txt data/index --batch 5000

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default settings
INPUT_FILE=""
OUTPUT_DIR=""
TAGGER="udpipe"
LANGUAGE="en"
BATCH_SIZE=1000
FORMAT="text"  # text, conllu

print_usage() {
    echo "Usage: $0 <input_file> <output_dir> [OPTIONS]"
    echo ""
    echo "Arguments:"
    echo "  input_file     Input file (line-segmented text or CoNLL-U)"
    echo "  output_dir     Output directory for Lucene index"
    echo ""
    echo "Options:"
    echo "  --tagger T     Tagger to use: simple (default: udpipe)"
    echo "                 'udpipe' - Best results, requires UDPipe model"
    echo "                 'simple' - Fallback, limited coverage"
    echo "  --language L   Language code for UDPipe (default: en)"
    echo "                 Options: en, pl, de, es, fr, it, pt, ru, cs, etc."
    echo "  --format F     Input format: text (default), conllu"
    echo "                 'text'   - One sentence per line"
    echo "                 'conllu' - CoNLL-U format (pre-tagged)"
    echo "  --batch N      Batch size for indexing (default: 1000)"
    echo "  --help         Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 sentences.txt data/index --tagger udpipe --language en"
    echo "  $0 corpus.conllu data/index --format conllu"
    echo "  $0 sentences.txt data/index --tagger simple"
}

log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --tagger)
            TAGGER="$2"
            shift 2
            ;;
        --language)
            LANGUAGE="$2"
            shift 2
            ;;
        --format)
            FORMAT="$2"
            shift 2
            ;;
        --batch)
            BATCH_SIZE="$2"
            shift 2
            ;;
        --help)
            print_usage
            exit 0
            ;;
        -*)
            log_error "Unknown option: $1"
            print_usage
            exit 1
            ;;
        *)
            if [[ -z "$INPUT_FILE" ]]; then
                INPUT_FILE="$1"
            elif [[ -z "$OUTPUT_DIR" ]]; then
                OUTPUT_DIR="$1"
            else
                log_error "Unexpected argument: $1"
                print_usage
                exit 1
            fi
            shift
            ;;
    esac
done

# Validate required arguments
if [[ -z "$INPUT_FILE" ]]; then
    log_error "Input file is required"
    print_usage
    exit 1
fi

if [[ -z "$OUTPUT_DIR" ]]; then
    log_error "Output directory is required"
    print_usage
    exit 1
fi

# Check input file exists
if [[ ! -f "$INPUT_FILE" ]]; then
    log_error "Input file not found: $INPUT_FILE"
    exit 1
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Get project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"

log_info "=========================================="
log_info "ConceptSketch - Corpus Indexing"
log_info "=========================================="
log_info ""
log_info "Input file:  $INPUT_FILE"
log_info "Output dir:  $OUTPUT_DIR"
log_info "Format:      $FORMAT"
log_info "Tagger:      $TAGGER"

if [[ "$FORMAT" == "text" ]]; then
    log_info "Language:    $LANGUAGE"
fi

log_info "Batch size:  $BATCH_SIZE"
log_info ""

# Build the project first
log_info "Building project..."
if ! mvn -f "$PROJECT_ROOT/pom.xml" clean package -DskipTests -q 2>&1; then
    log_error "Build failed"
    exit 1
fi
log_success "Build complete"

# Determine the command to run
JAR_FILE="$PROJECT_ROOT/target/concept-sketch-1.0.0.jar"

if [[ ! -f "$JAR_FILE" ]]; then
    JAR_FILE="$PROJECT_ROOT/target/concept-sketch-*.jar"
    if ls $JAR_FILE 1> /dev/null 2>&1; then
        JAR_FILE=$(ls $JAR_FILE | head -1)
    else
        log_error "JAR file not found. Run 'mvn package' first."
        exit 1
    fi
fi

# Run the appropriate indexing command
if [[ "$FORMAT" == "conllu" ]]; then
    log_info ""
    log_info "Indexing CoNLL-U file..."

    java -jar "$JAR_FILE" conllu \
        --input "$INPUT_FILE" \
        --output "$OUTPUT_DIR" \
        --commit "$BATCH_SIZE"

elif [[ "$TAGGER" == "udpipe" ]]; then
    log_info ""
    log_info "Indexing text with UDPipe tagger..."

    java -jar "$JAR_FILE" index \
        --corpus "$INPUT_FILE" \
        --output "$OUTPUT_DIR" \
        --language "$LANGUAGE" \
        --batch "$BATCH_SIZE"

else
    log_info ""
    log_info "Indexing text with simple tagger..."

    java -jar "$JAR_FILE" index \
        --corpus "$INPUT_FILE" \
        --output "$OUTPUT_DIR" \
        --language simple \
        --batch "$BATCH_SIZE"
fi

log_success ""
log_success "Indexing complete!"
log_info ""
log_info "Index location: $OUTPUT_DIR"
log_info ""
log_info "Usage examples:"
log_info "  # Query word sketch"
log_info "  java -jar $JAR_FILE query --index $OUTPUT_DIR --lemma house"
log_info ""
log_info "  # Start API server"
log_info "  java -jar $JAR_FILE server --index $OUTPUT_DIR"
