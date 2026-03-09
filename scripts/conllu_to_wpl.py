#!/usr/bin/env python3
"""
Convert CoNLL-U format to tabular WPL format with explicit <s>/<s> sentence markers.

BlackLab's built-in CoNLL-U parser does not emit <s> inline tags from blank-line
sentence boundaries. This script preprocesses the CoNLL-U file so that
BlackLab's tabular parser (fileType: tabular, inlineTags: true) can index
sentence spans, enabling context=s for sentence-bounded concordances.

Input format (CoNLL-U):
    # sent_id = 1           <- optional comment lines, skipped
    1   The   the   DET   DT   ...
    2   cat   cat   NOUN  NN   ...
                            <- blank line = sentence boundary
    1   A     a     DET   DT   ...
    ...

Output format (tabular with sentence markers):
    <s>
    The\tthe\tDET\tDT\t...
    cat\tcat\tNOUN\tNN\t...
    </s>
    <s>
    A\ta\tDET\tDT\t...
    ...
    </s>

Note: multi-word token lines (ID like "1-2") and empty node lines (ID like "1.1")
are dropped since they are not real tokens.

Usage:
    python scripts/conllu_to_wpl.py input.conllu output_s.conllu
"""

import sys
import re


def convert(input_path: str, output_path: str) -> tuple[int, int]:
    """Convert CoNLL-U to WPL with <s> markers. Returns (sentence_count, token_count)."""
    mwt_or_empty = re.compile(r'^\d+-\d+\t|^\d+\.\d+\t')
    sentences = 0
    tokens = 0
    in_sentence = False

    with open(input_path, encoding='utf-8', errors='replace') as fin, \
         open(output_path, 'w', encoding='utf-8') as fout:

        for line in fin:
            line = line.rstrip('\n')

            # Skip comment lines
            if line.startswith('#'):
                continue

            # Blank line = sentence boundary
            if not line.strip():
                if in_sentence:
                    fout.write('</s>\n')
                    sentences += 1
                    in_sentence = False
                continue

            # Skip multi-word tokens ("1-2\t...") and empty nodes ("1.1\t...")
            if mwt_or_empty.match(line):
                continue

            # Normal token line — open sentence if needed
            if not in_sentence:
                fout.write('<s>\n')
                in_sentence = True

            fout.write(line + '\n')
            tokens += 1

        # Close final sentence if file doesn't end with a blank line
        if in_sentence:
            fout.write('</s>\n')
            sentences += 1

    return sentences, tokens


def main():
    if len(sys.argv) != 3:
        print(f'Usage: {sys.argv[0]} <input.conllu> <output_s.conllu>', file=sys.stderr)
        sys.exit(1)

    input_path, output_path = sys.argv[1], sys.argv[2]
    print(f'Converting {input_path} -> {output_path} ...', flush=True)
    sentences, tokens = convert(input_path, output_path)
    print(f'Done: {sentences:,} sentences, {tokens:,} tokens written.')


if __name__ == '__main__':
    main()
