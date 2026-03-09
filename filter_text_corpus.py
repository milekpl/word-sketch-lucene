#!/usr/bin/env python3
"""
Filter plain text corpus (one sentence per line) by removing boilerplate
sentences and cleaning artifacts.

Usage:
    python filter_text_corpus.py input.txt output.txt [--patterns file.txt]
        [--limit N] [--no-strip-caret]

The script streams the file line by line, so it works with files too large
to fit in memory (tens of GBs).

Matching is simple substring/prefix matching - if the sentence STARTS WITH
any of the patterns, it is removed.

By default the script also removes the '^' character from each line, which
appears in some corpora (e.g. Lynx output where carets mark removed links).
Use `--no-strip-caret` to disable this behavior.

Optional --limit N stops after writing N sentences (useful for creating test corpora).
"""

import argparse
import sys
from pathlib import Path

# Default boilerplate patterns to filter (sentence prefixes)
DEFAULT_PATTERNS = [
    "frontiers-fpsyg-corpus.txt Journal Information",
    "Journal ID (publisher-id):",
    "Psychology Journal Abbreviation:",
    "Psychology ISSN:",
    "Publisher: Frontiers Research Foundation",
    "Article Information",
    "Copyright",
    "open-access:",
    "Received Day:",
    "Accepted Day:",
    "Electronic publication date:",
    "collection publication date:",
    "Volume:",
    "DOI:",
    "[doi:",
    "Front.",
    "____",
    "______",
    "________________________________________________________________",
    "____________________________________________________________________________________",
]


def load_patterns(pattern_file: str) -> list[str]:
    """Load patterns from a file, one per line."""
    patterns = []
    with open(pattern_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                patterns.append(line)
    return patterns


def matches_boilerplate(text: str, patterns: list[str]) -> bool:
    """
    Check if the text starts with any of the boilerplate patterns.

    Simple prefix matching - if text starts with any pattern, return True.
    """
    for pattern in patterns:
        if text.startswith(pattern):
            return True
    return False


def filter_text_stream(
    input_path: str,
    output_path: str,
    patterns: list[str],
    limit: int | None = None,
    buffer_size: int = 8192,
    strip_caret: bool = True
) -> tuple[int, int]:
    """
    Stream-filter a plain text corpus, removing boilerplate sentences.

    Args:
        input_path: Path to input text file (one sentence per line)
        output_path: Path to output filtered text file
        patterns: List of string prefixes to filter
        limit: Maximum number of sentences to write (None = no limit)
        buffer_size: Buffer size for reading/writing
        strip_caret: if True (default) strip the '^' character from each line
            before processing. This helps clean up artifacts such as Lynx's
            caret markers for removed links.

    Returns:
        Tuple of (total_written, removed_count)
    """
    total = 0
    removed = 0

    with open(input_path, "r", encoding="utf-8", buffering=buffer_size) as infile, \
         open(output_path, "w", encoding="utf-8", buffering=buffer_size) as outfile:

        for line in infile:
            # Check if we've hit the limit
            if limit is not None and total >= limit:
                break

            text = line.rstrip('\r\n')

            # optionally strip unwanted caret artifacts
            if strip_caret and '^' in text:
                text = text.replace('^', '')
            
            # Skip empty lines
            if not text.strip():
                continue
            
            # Check for boilerplate
            if matches_boilerplate(text, patterns):
                removed += 1
                continue
            
            # Write the sentence
            outfile.write(text + '\n')
            total += 1
            
            # Progress reporting for large files
            if total % 10000 == 0:
                sys.stderr.write(f"\rWritten {total:,} sentences...")
                sys.stderr.flush()

    sys.stderr.write("\n")
    return total, removed


def main():
    parser = argparse.ArgumentParser(
        description="Filter plain text corpus by removing boilerplate sentences."
    )
    parser.add_argument(
        "input", help="Input text file path (one sentence per line)"
    )
    parser.add_argument(
        "output", help="Output filtered text file path"
    )
    parser.add_argument(
        "--patterns", "-p",
        help="File containing patterns (one per line), or use default patterns"
    )
    parser.add_argument(
        "--limit", "-l",
        type=int,
        default=None,
        help="Maximum number of sentences to write (default: no limit)"
    )
    parser.add_argument(
        "--strip-caret",
        dest="strip_caret",
        action="store_true",
        help="Remove '^' characters from each line (default)"
    )
    parser.add_argument(
        "--no-strip-caret",
        dest="strip_caret",
        action="store_false",
        help="Do not remove '^' characters from lines"
    )
    parser.set_defaults(strip_caret=True)
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Show progress information"
    )


    args = parser.parse_args()

    # Load patterns
    if args.patterns:
        patterns = load_patterns(args.patterns)
        if args.verbose:
            print(f"Loaded {len(patterns)} patterns from {args.patterns}", file=sys.stderr)
    else:
        patterns = DEFAULT_PATTERNS
        if args.verbose:
            print(f"Using {len(patterns)} default patterns", file=sys.stderr)

    # Verify files exist
    if not Path(args.input).exists():
        sys.exit(f"Error: Input file '{args.input}' not found")

    # Check if input equals output (dangerous)
    if Path(args.input).resolve() == Path(args.output).resolve():
        sys.exit("Error: Input and output files are the same - aborting to prevent data loss")

    # Process the file
    if args.verbose:
        limit_msg = f" (limit: {args.limit})" if args.limit else ""
        print(f"Processing {args.input} -> {args.output}{limit_msg}...", file=sys.stderr)

    total, removed = filter_text_stream(
        args.input,
        args.output,
        patterns,
        args.limit,
        strip_caret=args.strip_caret
    )

    if args.verbose:
        print(f"Done. Wrote {total:,} sentences, removed {removed:,} boilerplate sentences.", file=sys.stderr)
    else:
        # Minimal output for scripting
        print(f"{total}\t{removed}")


if __name__ == "__main__":
    main()
