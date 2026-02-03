# Server Startup Scripts

Quick-start scripts for running Word Sketch Lucene.

## Features

- **Auto-Detection**: Scripts automatically detect `collocations_v2.bin` for PRECOMPUTED queries
- **No File Copies**: Directly opens collocations files without copying
- **Cross-Platform**: PowerShell (Windows) and Bash (Linux/Mac)
- **Full Stack Option**: Start both API and web UI with one command
- **Customizable**: Override ports, index location, and other parameters

## Quick Start

### Windows (PowerShell)

Start just the API server:
```powershell
.\scripts\start-server.ps1
```

Start API server + web UI (recommended):
```powershell
.\scripts\start-all.ps1
```

### Linux/Mac (Bash)

Start just the API server:
```bash
chmod +x scripts/start-server.sh
./scripts/start-server.sh
```

Start API server + web UI (recommended):
```bash
chmod +x scripts/start-all.sh
./scripts/start-all.sh
```

## Automatic Collocations Setup

The scripts automatically detect collocations for fast PRECOMPUTED queries:

1. **Detection**: Scripts look for `collocations_v2.bin` in the index directory
2. **Passing**: If found, passed to server via `--collocations` parameter
3. **Algorithm Selection**:
   - ✓ Collocations file detected → **PRECOMPUTED** (O(1) instant lookup)
   - ✗ No collocations file → **SPAN_COUNT** (slower on-the-fly queries)

This means you can run the scripts with no additional configuration - the server opens the file directly from its location using the `--collocations` parameter.

## Customization

### Change Port
```powershell
# API on port 9090, Web on port 3001
.\scripts\start-all.ps1 -Port 9090 -WebPort 3001
```

### Change Index Location
```powershell
.\scripts\start-server.ps1 -Index "C:\path\to\my\index"
```

## Configuration

Edit the default `$Index` path in the script files to change the default index:
- `start-server.ps1` (line 10)
- `start-server.sh` (line 5)
- `start-all.ps1` (line 10)
- `start-all.sh` (line 5)

## Build First

Before running, ensure the JAR is built:
```bash
mvn clean package
```

## What Gets Ignored

The `.gitignore` in this directory ignores:
- `*.bin` - Precomputed collocation files
- `*.log` - Server logs
- `*.pid` - Process ID files
- `index-*/` - Local index directories

This keeps the repository clean while allowing you to store local configuration and precomputed data.
