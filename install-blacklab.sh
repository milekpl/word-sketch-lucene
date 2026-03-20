#!/bin/bash
# Install BlackLab 4.0.0 JARs from GitHub releases into the local Maven repository.
#
# pom.xml requires:
#   nl.inl.blacklab:blacklab-engine:4.0.0
#   nl.inl.blacklab:blacklab-query-parser:4.0.0
#
# BlackLab 4.x is NOT published to Maven Central; distribute via GitHub releases.
# Run this once before `mvn compile` or `mvn test`.

set -e

BLACKLAB_VERSION="4.0.0"
DOWNLOAD_URL="https://github.com/instituutnederlandsetaal/BlackLab/releases/download/v${BLACKLAB_VERSION}/blacklab-${BLACKLAB_VERSION}-jar.zip"
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

echo "=== Installing BlackLab ${BLACKLAB_VERSION} ==="
echo "Downloading from: ${DOWNLOAD_URL}"

curl -L --fail -o "${TEMP_DIR}/blacklab.zip" "${DOWNLOAD_URL}"

echo "Extracting..."
unzip -q "${TEMP_DIR}/blacklab.zip" -d "${TEMP_DIR}"

# Locate the JARs — they may be at the root or under a lib/ subdirectory.
SEARCH_ROOT="${TEMP_DIR}"

install_jar() {
    local artifact_id="$1"
    local jar_pattern="$2"
    local jar_file
    jar_file=$(find "${SEARCH_ROOT}" -name "${jar_pattern}" | head -1)
    if [ -z "$jar_file" ]; then
        echo "  WARNING: ${jar_pattern} not found — skipping"
        return
    fi
    echo "  Installing nl.inl.blacklab:${artifact_id}:${BLACKLAB_VERSION} ..."
    mvn --batch-mode install:install-file \
        -Dfile="$jar_file" \
        -DgroupId=nl.inl.blacklab \
        "-DartifactId=${artifact_id}" \
        "-Dversion=${BLACKLAB_VERSION}" \
        -Dpackaging=jar \
        -q
}

echo ""
echo "Installing required artifacts..."
install_jar "blacklab-engine"       "blacklab-engine-${BLACKLAB_VERSION}.jar"
install_jar "blacklab-query-parser" "blacklab-query-parser-${BLACKLAB_VERSION}.jar"

echo ""
echo "Installing optional supporting artifacts..."
install_jar "blacklab-util"         "blacklab-util-${BLACKLAB_VERSION}.jar"
install_jar "blacklab-common"       "blacklab-common-${BLACKLAB_VERSION}.jar"
install_jar "blacklab-content-store" "blacklab-content-store-${BLACKLAB_VERSION}.jar"

echo ""
echo "=== BlackLab ${BLACKLAB_VERSION} installed successfully ==="
echo ""
echo "Required artifacts now in ~/.m2:"
echo "  nl.inl.blacklab:blacklab-engine:${BLACKLAB_VERSION}"
echo "  nl.inl.blacklab:blacklab-query-parser:${BLACKLAB_VERSION}"
