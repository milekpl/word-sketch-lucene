#!/bin/bash
# Install BlackLab 4.0.0 JARs from GitHub releases
# This script downloads the pre-built JARs and installs them to local Maven repository

set -e

BLACKLAB_VERSION="4.0.0"
DOWNLOAD_URL="https://github.com/instituutnederlandsetaal/BlackLab/releases/download/v${BLACKLAB_VERSION}/blacklab-${BLACKLAB_VERSION}-jar.zip"
TEMP_DIR=$(mktemp -d)

echo "=== Installing BlackLab ${BLACKLAB_VERSION} ==="
echo "Download URL: ${DOWNLOAD_URL}"
echo "Temp directory: ${TEMP_DIR}"
echo ""

# Download
echo "Downloading BlackLab..."
curl -L -o "${TEMP_DIR}/blacklab.zip" "${DOWNLOAD_URL}"

# Extract
echo "Extracting..."
unzip -q "${TEMP_DIR}/blacklab.zip" -d "${TEMP_DIR}"

# The zip contains:
# - blacklab-4.0.0.jar (empty wrapper)
# - lib/ directory with all dependencies including:
#   - blacklab-engine-4.0.0.jar (main library)
#   - blacklab-util-4.0.0.jar
#   - blacklab-common-4.0.0.jar
#   - lucene-*.jar (all Lucene dependencies)
#   - Other third-party libraries

LIB_DIR="${TEMP_DIR}/lib"

if [ ! -d "$LIB_DIR" ]; then
    echo "ERROR: lib/ directory not found in zip!"
    exit 1
fi

echo "Installing BlackLab libraries..."

# Install blacklab-engine as blacklab-core
ENGINE_JAR=$(find "${LIB_DIR}" -name "blacklab-engine-${BLACKLAB_VERSION}.jar" | head -1)
if [ -n "$ENGINE_JAR" ]; then
    echo "  Installing blacklab-core (from blacklab-engine)..."
    mvn install:install-file \
        -Dfile="$ENGINE_JAR" \
        -DgroupId=nl.inl.blacklab \
        -DartifactId=blacklab-core \
        -Dversion=${BLACKLAB_VERSION} \
        -Dpackaging=jar
fi

# Install blacklab-util
UTIL_JAR=$(find "${LIB_DIR}" -name "blacklab-util-${BLACKLAB_VERSION}.jar" | head -1)
if [ -n "$UTIL_JAR" ]; then
    echo "  Installing blacklab-util..."
    mvn install:install-file \
        -Dfile="$UTIL_JAR" \
        -DgroupId=nl.inl.blacklab \
        -DartifactId=blacklab-util \
        -Dversion=${BLACKLAB_VERSION} \
        -Dpackaging=jar
fi

# Install blacklab-common
COMMON_JAR=$(find "${LIB_DIR}" -name "blacklab-common-${BLACKLAB_VERSION}.jar" | head -1)
if [ -n "$COMMON_JAR" ]; then
    echo "  Installing blacklab-common..."
    mvn install:install-file \
        -Dfile="$COMMON_JAR" \
        -DgroupId=nl.inl.blacklab \
        -DartifactId=blacklab-common \
        -Dversion=${BLACKLAB_VERSION} \
        -Dpackaging=jar
fi

# Install Lucene libraries
echo ""
echo "Installing Lucene libraries..."
for jar in lucene-core lucene-analyzers-common lucene-highlighter lucene-memory lucene-queries lucene-queryparser lucene-sandbox lucene-misc lucene-backward-codecs; do
    JAR_FILE=$(find "${LIB_DIR}" -name "${jar}-8.11.1.jar" | head -1)
    if [ -n "$JAR_FILE" ]; then
        echo "  Installing ${jar}..."
        mvn install:install-file \
            -Dfile="$JAR_FILE" \
            -DgroupId=org.apache.lucene \
            -DartifactId="${jar}" \
            -Dversion=8.11.1 \
            -Dpackaging=jar
    fi
done

# Cleanup
rm -rf "${TEMP_DIR}"

echo ""
echo "=== BlackLab ${BLACKLAB_VERSION} installed successfully ==="
echo ""
echo "Dependencies installed:"
echo "  - nl.inl.blacklab:blacklab-core:${BLACKLAB_VERSION}"
echo "  - nl.inl.blacklab:blacklab-util:${BLACKLAB_VERSION}"
echo "  - nl.inl.blacklab:blacklab-common:${BLACKLAB_VERSION}"
echo "  - org.apache.lucene:lucene-*:8.11.1"
echo ""
echo "Add to your pom.xml:"
echo ""
echo "  <dependency>"
echo "    <groupId>nl.inl.blacklab</groupId>"
echo "    <artifactId>blacklab-core</artifactId>"
echo "    <version>${BLACKLAB_VERSION}</version>"
echo "  </dependency>"
echo ""
