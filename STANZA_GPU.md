# Stanza GPU Setup

## Quick Install (CUDA 11.8)

```bash
# Install PyTorch with CUDA support and Stanza
pip install -r requirements-stanza-gpu.txt
```

## Manual Install

### 1. Install PyTorch with CUDA

**CUDA 11.8:**
```bash
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
```

**CUDA 12.1:**
```bash
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
```

### 2. Install Stanza

```bash
pip install stanza
```

### 3. Verify GPU Access

```bash
python -c "import torch; print(f'CUDA: {torch.cuda.is_available()}'); print(f'GPU: {torch.cuda.get_device_name(0) if torch.cuda.is_available() else \"None\"}')"
```

Expected output:
```
CUDA: True
GPU: NVIDIA GeForce RTX 3080
```

---

## Usage

### Tag Corpus with GPU

```bash
# Download model first (one-time)
python tag_with_stanza.py --download --lang en

# Tag corpus (automatically uses GPU)
python tag_with_stanza.py \
  --input corpus.txt \
  --output corpus.conllu \
  --lang en \
  --batch-size 64
```

### GPU Options

| Option | Default | Description |
|--------|---------|-------------|
| `--batch-size` | 64 | Larger = more GPU memory, faster processing |
| `--cpu` | off | Force CPU usage (disable GPU) |
| `--download` | off | Download model before processing |

### Tuning for Your GPU

**24GB GPU (RTX 3090/4090):**
```bash
python tag_with_stanza.py -i corpus.txt -o corpus.conllu --batch-size 128
```

**12GB GPU (RTX 3060/4070):**
```bash
python tag_with_stanza.py -i corpus.txt -o corpus.conllu --batch-size 64
```

**8GB GPU (RTX 3070/4060):**
```bash
python tag_with_stanza.py -i corpus.txt -o corpus.conllu --batch-size 32
```

**4GB GPU or less:**
```bash
python tag_with_stanza.py -i corpus.txt -o corpus.conllu --batch-size 16
```

---

## Performance Comparison

| GPU | Batch Size | Sentences/sec | Time for 1M sentences |
|-----|------------|---------------|----------------------|
| RTX 3090 | 128 | ~500 | ~33 min |
| RTX 3080 | 64 | ~350 | ~48 min |
| RTX 3060 | 64 | ~200 | ~83 min |
| CPU (8 cores) | 1 | ~20 | ~14 hours |

---

## Troubleshooting

### "CUDA out of memory"

Reduce batch size:
```bash
python tag_with_stanza.py -i corpus.txt -o corpus.conllu --batch-size 32
```

### "CUDA not available"

1. Check PyTorch installation:
   ```bash
   python -c "import torch; print(torch.__version__)"
   ```
   Should show `+cu118` or `+cu121` suffix.

2. Reinstall with correct CUDA version:
   ```bash
   pip uninstall torch torchvision torchaudio
   pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
   ```

3. Check NVIDIA driver:
   ```bash
   nvidia-smi
   ```
   Should show driver version and CUDA version.

### Slow GPU utilization

- Increase batch size (`--batch-size 128`)
- Ensure corpus is large enough (GPU overhead for small files)
- Check GPU usage with `nvidia-smi` during processing

---

## Download Models

Models are downloaded automatically on first use. To pre-download:

```bash
python -c "import stanza; stanza.download('en')"
```

For multiple languages:
```bash
python -c "import stanza; stanza.download('en'); stanza.download('de'); stanza.download('fr')"
```

---

## References

- [Stanza Documentation](https://stanfordnlp.github.io/stanza/)
- [PyTorch CUDA Installation](https://pytorch.org/get-started/locally/)
- [NVIDIA CUDA Toolkit](https://developer.nvidia.com/cuda-toolkit)
