#!/usr/bin/env python3
"""
Tag a corpus with Stanza (tokenization, POS, lemma, dependency parsing).
Outputs CoNLL-U format suitable for BlackLab indexing.

Uses GPU (CUDA) if available for faster processing.

Usage:
    python tag_with_stanza.py --input corpus.txt --output corpus.conllu --lang en

Requirements:
    pip install stanza torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
"""

import argparse
import gc
import stanza
import sys
from pathlib import Path
import torch


def check_cuda():
    """Check CUDA availability and print GPU info."""
    if torch.cuda.is_available():
        gpu_count = torch.cuda.device_count()
        gpu_name = torch.cuda.get_device_name(0)
        gpu_memory = torch.cuda.get_device_properties(0).total_memory / (1024**3)
        print(f"✓ CUDA available: {gpu_count} GPU(s)")
        print(f"  GPU 0: {gpu_name} ({gpu_memory:.1f} GB)")
        print(f"  CUDA version: {torch.version.cuda}")
        return True
    else:
        print("✗ CUDA not available - will use CPU (slower)")
        print("  To enable GPU: pip install torch --index-url https://download.pytorch.org/whl/cu118")
        return False


def stream_units(input_path: Path, paragraph_mode: bool = False):
    """Yield processing units from the input file.

    paragraph_mode=False (default): yield one non-empty line at a time.
        Use this for corpora formatted as one sentence per line.
    paragraph_mode=True: yield blank-line-separated paragraph blocks.
        Use this when sentences span multiple lines within a paragraph.
    """
    if not paragraph_mode:
        with open(input_path, 'r', encoding='utf-8') as f:
            for line in f:
                stripped = line.rstrip('\n')
                if stripped:
                    yield stripped
    else:
        buf = []
        with open(input_path, 'r', encoding='utf-8') as f:
            for line in f:
                stripped = line.rstrip('\n')
                if stripped == '':
                    if buf:
                        yield '\n'.join(buf)
                        buf = []
                else:
                    buf.append(stripped)
            if buf:
                yield '\n'.join(buf)



def main():
    parser = argparse.ArgumentParser(
        description='Tag corpus with Stanza (tokenize, POS, lemma, depparse) using GPU'
    )
    parser.add_argument(
        '--input', '-i',
        required=True,
        type=Path,
        help='Input text file (one sentence per line or paragraph)'
    )
    parser.add_argument(
        '--output', '-o',
        required=True,
        type=Path,
        help='Output CoNLL-U file'
    )
    parser.add_argument(
        '--lang', '-l',
        default='en',
        help='Language code (default: en)'
    )
    parser.add_argument(
        '--download',
        action='store_true',
        help='Download the model before processing'
    )
    parser.add_argument(
        '--cpu',
        action='store_true',
        help='Force CPU usage (disable GPU)'
    )
    parser.add_argument(
        '--batch-size',
        type=int,
        default=5000,
        help='Internal sentence batch size for the NLP model (GPU throughput tuning, default: 5000)'
    )
    parser.add_argument(
        '--progress',
        type=int,
        default=1000,
        help='Print progress every N lines/paragraphs (default: 1000)'
    )
    parser.add_argument(
        '--paragraph-mode',
        action='store_true',
        help='Input uses blank-line-separated paragraphs instead of one sentence per line'
    )
    parser.add_argument(
        '--chunk-size',
        type=int,
        default=5000,
        help='Number of lines/paragraphs to batch together per nlp() call (default: 5000)'
    )
    parser.add_argument(
        '--heartbeat',
        type=int,
        default=20,
        help='Force gc.collect() + VRAM flush every N chunks (default: 20)'
    )
    
    args = parser.parse_args()
    
    # Validate input
    if not args.input.exists():
        print(f"Error: Input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)
    
    # Create output directory if needed
    args.output.parent.mkdir(parents=True, exist_ok=True)
    
    # Check CUDA
    use_cuda = not args.cpu and torch.cuda.is_available()
    check_cuda()
    
    # Download model if requested
    if args.download:
        print(f"Downloading Stanza model for {args.lang}...")
        stanza.download(args.lang)
    
    # Initialize pipeline with GPU support
    print(f"Initializing Stanza pipeline for {args.lang}...")
    print(f"Processors: tokenize, pos, lemma, depparse")
    print(f"Device: {'GPU (CUDA)' if use_cuda else 'CPU'}")
    print(f"Model token batch size: {args.batch_size}")
    
    try:
        nlp = stanza.Pipeline(
            lang=args.lang,
            processors='tokenize,pos,lemma,depparse',
            verbose=False,
            use_gpu=use_cuda and not args.cpu,
            gpu_memory_fraction=0.9,
            # Global default + per-processor overrides (depparse is the bottleneck)
            batch_size=args.batch_size,
            tokenize_batch_size=args.batch_size,
            pos_batch_size=args.batch_size,
            lemma_batch_size=args.batch_size,
            depparse_batch_size=args.batch_size,
        )
    except Exception as e:
        print(f"Error initializing Stanza: {e}", file=sys.stderr)
        if not use_cuda:
            print("GPU not available, falling back to CPU...")
            try:
                nlp = stanza.Pipeline(
                    lang=args.lang,
                    processors='tokenize,pos,lemma,depparse',
                    verbose=False,
                    use_gpu=False,
                    batch_size=args.batch_size,
                )
            except Exception as e2:
                print(f"Error initializing Stanza on CPU: {e2}", file=sys.stderr)
                print("Try running with --download flag to download the model first.", file=sys.stderr)
                sys.exit(1)
            use_cuda = False
        else:
            print("Try running with --download flag to download the model first.", file=sys.stderr)
            sys.exit(1)

    if use_cuda:
        print(f"✓ Stanza pipeline active on GPU: {torch.cuda.get_device_name(0)}")
    else:
        print("✓ Stanza pipeline active on CPU")

    # Process and write output (chunked batching to prevent handle exhaustion)
    print(f"Streaming input: {args.input}")
    print(f"Chunk size: {args.chunk_size} units per nlp() call, heartbeat every {args.heartbeat} chunks")

    def write_doc(doc, f, start_sent_id):
        """Write a processed Stanza doc to CoNLL-U output, return (sentences, tokens) counts."""
        sc = tc = 0
        for sentence in doc.sentences:
            sc += 1
            tc += len(sentence.words)
            sent_id = start_sent_id + sc
            print(f"# sent_id = {sent_id}", file=f)
            print(f"# text = {sentence.text}", file=f)
            for word in sentence.words:
                misc = f"Text={word.text}" if word.text else ""
                print(
                    word.id,
                    word.text,
                    word.lemma if word.lemma else '_',
                    word.upos if word.upos else '_',
                    word.xpos if word.xpos else '_',
                    word.feats if word.feats else '_',
                    word.head,
                    word.deprel if word.deprel else '_',
                    '_',
                    misc,
                    sep='\t',
                    file=f
                )
            print(file=f)  # blank line after each sentence
        return sc, tc

    accumulated_lines = []
    with open(args.output, 'w', encoding='utf-8') as f:
        sentence_count = 0
        token_count = 0
        para_count = 0
        chunk_count = 0

        def flush_chunk(chunk, f):
            nonlocal sentence_count, token_count, chunk_count
            combined_text = "\n\n".join(chunk)
            doc = nlp(combined_text)
            sc, tc = write_doc(doc, f, sentence_count)
            sentence_count += sc
            token_count += tc
            chunk_count += 1
            del doc

            if chunk_count % args.heartbeat == 0:
                if use_cuda:
                    torch.cuda.empty_cache()
                gc.collect()
                f.flush()
                print(f"  ✓ Heartbeat: {para_count:,} units | {sentence_count:,} sentences | {token_count:,} tokens")

        for para in stream_units(args.input, args.paragraph_mode):
            accumulated_lines.append(para)
            para_count += 1

            if len(accumulated_lines) >= args.chunk_size:
                flush_chunk(accumulated_lines, f)
                accumulated_lines = []

            if para_count % args.progress == 0:
                print(f"  Processed {para_count:,} units ({sentence_count:,} sentences, {token_count:,} tokens)...")

        # Flush any remaining units
        if accumulated_lines:
            flush_chunk(accumulated_lines, f)
            accumulated_lines = []

        print(f"\nComplete!")
        print(f"  Paragraphs: {para_count:,}")
        print(f"  Sentences: {sentence_count:,}")
        print(f"  Tokens: {token_count:,}")
        print(f"  Output: {args.output}")


if __name__ == '__main__':
    main()
