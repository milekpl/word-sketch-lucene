#!/bin/bash
# Start Word Sketch Lucene API Server
# Usage: ./start-server.sh [--port 8080] [--index /path/to/index] [--collocations /path/to/collocations.bin]

PORT=8080
INDEX="d:\corpus_74m\index-hybrid"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port)
            PORT="$2"
            shift 2
            ;;
        --index)
            INDEX="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Check if JAR exists
JAR_FILE="$PROJECT_ROOT/target/word-sketch-lucene-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found: $JAR_FILE"
    echo "Please build with: mvn clean package"
    exit 1
fi

# Check if index exists
if [ ! -d "$INDEX" ]; then
    echo "ERROR: Index directory not found: $INDEX"
    exit 1
fi

# Auto-detect collocations file (optional parameter)
COLLOCATIONS=""
COLLOCATIONS_SRC="$INDEX/collocations_v2.bin"
if [ -f "$COLLOCATIONS_SRC" ]; then
    COLLOCATIONS="$COLLOCATIONS_SRC"
fi

echo ""
echo "================================"
echo "Word Sketch Lucene API Server"
echo "================================"
echo ""
echo "Configuration:"
echo "  Port:  $PORT"
echo "  Index: $INDEX"
if [ -n "$COLLOCATIONS" ]; then
    echo "  Algorithm: PRECOMPUTED (O(1) instant lookup)"
else
    echo "  Algorithm: SPAN_COUNT (on-the-fly queries)"
fi
echo ""
echo "Starting server..."
echo ""

# Start server
if [ -n "$COLLOCATIONS" ]; then
    java -jar "$JAR_FILE" server --index "$INDEX" --port "$PORT" --collocations "$COLLOCATIONS"
else
    java -jar "$JAR_FILE" server --index "$INDEX" --port "$PORT"
fi
