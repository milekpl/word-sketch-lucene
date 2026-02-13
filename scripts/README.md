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

Stop stale API server processes:

- No PowerShell (Windows CMD):
```cmd
scripts\kill-server.cmd
```
- PowerShell (recommended — shows command line, supports -Force and -WhatIf):
```powershell
scripts\kill-server.ps1  # defaults to port 8080
scripts\kill-server.ps1 -Port 8080 -Force
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

## Diagnostics Scripts

The `scripts/diagnostics` directory provides Phase-1 integrity tooling:

- `fingerprint.ps1` – creates `diagnostics/fingerprint.json` with index field fingerprint, stats, and collocations metadata.
- `integrity_snapshot.ps1` – captures API integrity baseline into:
   - `diagnostics/integrity_report.raw.json`
   - `diagnostics/integrity_summary.tsv`
   - `diagnostics/integrity_systemic_flag.json`
- `witness_pairs.ps1` – analyzes suspicious headword/collocate pairs from integrity report and writes `diagnostics/witness_<timestamp>.tsv`.

Example:
```powershell
# 1) fingerprint
.\scripts\diagnostics\fingerprint.ps1 -IndexPath "data/index"

# 2) integrity snapshot (server must be running)
.\scripts\diagnostics\integrity_snapshot.ps1 -ApiBase "http://localhost:8080" -Top 50

# 3) witness drill-down
.\scripts\diagnostics\witness_pairs.ps1 -IndexPath "data/index"
```

## What Gets Ignored

The `.gitignore` in this directory ignores:
- `*.bin` - Precomputed collocation files
- `*.log` - Server logs
- `*.pid` - Process ID files
- `index-*/` - Local index directories

This keeps the repository clean while allowing you to store local configuration and precomputed data.
