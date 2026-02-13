#!/usr/bin/env python3
"""
Filter large CoNLL-U files by removing sentences whose text starts with boilerplate.

Usage:
    python filter_conllu_boilerplate.py input.conllu output.conllu [--patterns file.txt]

The script streams the file line by line, so it works with files too large
to fit in memory (tens of GBs).

Matching is simple substring/prefix matching (not regex) - if the sentence
text STARTS WITH any of the patterns, the entire sentence is removed.
"""

import argparse
import sys
from pathlib import Path

# Default boilerplate patterns to filter (sentence text prefixes)
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
    # Common journal boilerplate
    "Front.",
    # Page separators (long underscore lines)
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


def extract_text_from_record(record_lines: list[str]) -> str | None:
    """Extract the # text = value from a CoNLL-U record."""
    for line in record_lines:
        if line.startswith("# text = "):
            return line[9:].rstrip("\r\n")  # Remove '# text = ' and trailing whitespace
    return None


def filter_conllu_stream(
    input_path: str,
    output_path: str,
    patterns: list[str],
    buffer_size: int = 8192
) -> tuple[int, int]:
    """
    Stream-filter a CoNLL-U file, removing records whose text starts with boilerplate.

    Args:
        input_path: Path to input CoNLL-U file
        output_path: Path to output filtered CoNLL-U file
        patterns: List of string prefixes to filter
        buffer_size: Buffer size for reading lines

    Returns:
        Tuple of (total_records, removed_records)
    """
    total = 0
    removed = 0
    current_record: list[str] = []

    with open(input_path, "r", encoding="utf-8", buffering=buffer_size) as infile, \
         open(output_path, "w", encoding="utf-8", buffering=buffer_size) as outfile:

        for line in infile:
            # Check if this is a record separator (empty line)
            if line.strip() == "":
                # Process the completed record
                if current_record:
                    text = extract_text_from_record(current_record)
                    if text is None or not matches_boilerplate(text, patterns):
                        # Keep this record (add empty line after for proper format)
                        outfile.write("".join(current_record))
                        outfile.write("\n")
                        total += 1
                    else:
                        removed += 1
                    current_record = []
            else:
                current_record.append(line)

        # Handle last record (file might not end with empty line)
        if current_record:
            text = extract_text_from_record(current_record)
            if text is None or not matches_boilerplate(text, patterns):
                # Keep this record (add empty line after for proper format)
                outfile.write("".join(current_record))
                outfile.write("\n")
                total += 1
            else:
                removed += 1

    return total, removed


def main():
    parser = argparse.ArgumentParser(
        description="Filter large CoNLL-U files by removing boilerplate sentences."
    )
    parser.add_argument(
        "input", help="Input CoNLL-U file path"
    )
    parser.add_argument(
        "output", help="Output CoNLL-U file path"
    )
    parser.add_argument(
        "--patterns", "-p",
        help="File containing patterns (one per line), or use default patterns"
    )
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
        print(f"Processing {args.input} -> {args.output}...", file=sys.stderr)

    total, removed = filter_conllu_stream(args.input, args.output, patterns)

    if args.verbose:
        print(f"Done. Kept {total} records, removed {removed} boilerplate records.", file=sys.stderr)
    else:
        # Minimal output for scripting
        print(f"{total}\t{removed}")


if __name__ == "__main__":
    main()
