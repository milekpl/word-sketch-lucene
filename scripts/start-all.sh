#!/bin/bash
# Start both API Server and Web UI
# Usage: ./start-all.sh [--port 8080] [--web-port 3000] [--index /path/to/index]

PORT=8080
WEB_PORT=3000
INDEX="d:\corpus_74m\index-hybrid"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port)
            PORT="$2"
            shift 2
            ;;
        --web-port)
            WEB_PORT="$2"
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

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

JAR_FILE="$PROJECT_ROOT/target/word-sketch-lucene-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found: $JAR_FILE"
    echo "Please build with: mvn clean package"
    exit 1
fi

if [ ! -d "$INDEX" ]; then
    echo "ERROR: Index directory not found: $INDEX"
    exit 1
fi

# Auto-detect collocations file
COLLOCATIONS=""
COLLOCATIONS_SRC="$INDEX/collocations_v2.bin"
if [ -f "$COLLOCATIONS_SRC" ]; then
    COLLOCATIONS="$COLLOCATIONS_SRC"
fi

echo ""
echo "================================"
echo "Word Sketch Lucene - Full Stack"
echo "================================"
echo ""
echo "Configuration:"
echo "  API Port:  $PORT"
echo "  Web Port:  $WEB_PORT"
if [ -n "$COLLOCATIONS" ]; then
    echo "  Algorithm: PRECOMPUTED (O(1) instant lookup)"
else
    echo "  Algorithm: SPAN_COUNT (on-the-fly queries)"
fi
echo ""
echo "Starting API Server (port $PORT)..."
echo "Starting Web Server (port $WEB_PORT)..."
echo ""

# Start API server in background
if [ -n "$COLLOCATIONS" ]; then
    java -jar "$JAR_FILE" server --index "$INDEX" --port "$PORT" --collocations "$COLLOCATIONS" &
else
    java -jar "$JAR_FILE" server --index "$INDEX" --port "$PORT" &
fi
API_PID=$!

# Give server time to start
sleep 3

# Start web server in background
cd "$PROJECT_ROOT/webapp"
python3 -m http.server $WEB_PORT &
WEB_PID=$!

echo ""
echo "✓ Services started successfully!"
echo ""
echo "API Server:"
echo "  http://localhost:$PORT/health"
echo ""
echo "Web Interface:"
echo "  http://localhost:$WEB_PORT"
echo ""
echo "Process IDs:"
echo "  API: $API_PID"
echo "  Web: $WEB_PID"
echo ""
echo "To stop: kill $API_PID $WEB_PID"
echo ""

# Wait for processes
wait $API_PID $WEB_PID
