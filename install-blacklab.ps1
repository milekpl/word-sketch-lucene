# Install BlackLab 4.0.0 JARs from GitHub releases
# PowerShell script for Windows

$BLACKLAB_VERSION = "4.0.0"
$DOWNLOAD_URL = "https://github.com/instituutnederlandsetaal/BlackLab/releases/download/v${BLACKLAB_VERSION}/blacklab-${BLACKLAB_VERSION}-jar.zip"
$TEMP_DIR = Join-Path $env:TEMP "blacklab-install"

Write-Host "=== Installing BlackLab ${BLACKLAB_VERSION} ==="
Write-Host "Download URL: ${DOWNLOAD_URL}"
Write-Host "Temp directory: ${TEMP_DIR}"
Write-Host ""

# Create temp directory
New-Item -ItemType Directory -Force -Path $TEMP_DIR | Out-Null

try {
    # Download
    Write-Host "Downloading BlackLab..."
    Invoke-WebRequest -Uri $DOWNLOAD_URL -OutFile "${TEMP_DIR}\blacklab.zip"

    # Extract
    Write-Host "Extracting..."
    Expand-Archive -Path "${TEMP_DIR}\blacklab.zip" -DestinationPath $TEMP_DIR -Force

    # Find JARs
    $BLACKLAB_CORE_JAR = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "blacklab-core-${BLACKLAB_VERSION}.jar" | Select-Object -First 1
    $BLACKLAB_SERVER_JAR = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "blacklab-server-${BLACKLAB_VERSION}.jar" | Select-Object -First 1
    $BLACKLAB_ALL_JAR = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "blacklab-all-${BLACKLAB_VERSION}.jar" | Select-Object -First 1

    # Install to Maven repository
    if ($BLACKLAB_CORE_JAR) {
        Write-Host "Installing blacklab-core..."
        mvn install:install-file `
            -Dfile="$($BLACKLAB_CORE_JAR.FullName)" `
            -DgroupId=nl.inl.blacklab `
            -DartifactId=blacklab-core `
            -Dversion=${BLACKLAB_VERSION} `
            -Dpackaging=jar
    }

    if ($BLACKLAB_SERVER_JAR) {
        Write-Host "Installing blacklab-server..."
        mvn install:install-file `
            -Dfile="$($BLACKLAB_SERVER_JAR.FullName)" `
            -DgroupId=nl.inl.blacklab `
            -DartifactId=blacklab-server `
            -Dversion=${BLACKLAB_VERSION} `
            -Dpackaging=jar
    }

    if ($BLACKLAB_ALL_JAR) {
        Write-Host "Installing blacklab-all..."
        mvn install:install-file `
            -Dfile="$($BLACKLAB_ALL_JAR.FullName)" `
            -DgroupId=nl.inl.blacklab `
            -DartifactId=blacklab-all `
            -Dversion=${BLACKLAB_VERSION} `
            -Dpackaging=jar
    }

    Write-Host ""
    Write-Host "=== BlackLab ${BLACKLAB_VERSION} installed successfully ===" -ForegroundColor Green
    Write-Host ""
    Write-Host "You can now use these dependencies in your pom.xml:"
    Write-Host ""
    Write-Host "  <dependency>"
    Write-Host "    <groupId>nl.inl.blacklab</groupId>"
    Write-Host "    <artifactId>blacklab-core</artifactId>"
    Write-Host "    <version>${BLACKLAB_VERSION}</version>"
    Write-Host "  </dependency>"
    Write-Host ""

} finally {
    # Cleanup
    if (Test-Path $TEMP_DIR) {
        Remove-Item -Recurse -Force $TEMP_DIR
    }
}
