# Word pairs for Unexpected Keyboard

Data branch, not code. It hosts the word pairs (bigram) files that the
keyboard downloads alongside a dictionary. They improve suggestions using
the previous word and let the keyboard suggest the next word after a space.

The files are downloaded from
`https://raw.githubusercontent.com/EDOLK/Unexpected-Keyboard/refs/heads/bigrams/v1/<dictionary>.bigrams`
and checked against the sizes and SHA-256 listed in `res/values/bigrams.xml`
on the code branch.

- `v1/` holds the files for version 1 of the format, compressed with gzip.
  The format is described in `scripts/gen_bigrams.py` on the code branch.
- `v1/SOURCES` says which word list each file was built from.
- `v1/sources/` has the source and license notes of those word lists.

## Source and licenses

Built from the word lists of
https://codeberg.org/Helium314/aosp-dictionaries at commit
`ff0cd52be8d9530200fefe8b296c53d4f662091e` (2026-10-08), the same project
the dictionaries are built from. Only the next-word data (`bigram=` lines)
is used, at most 5 following words per word.

The word lists come under different licenses, see `v1/sources/` and the
README of aosp-dictionaries. Most experimental lists are built from the
Leipzig Corpora Collection (https://wortschatz.uni-leipzig.de/) under
CC BY 4.0. `en_AU`, `en_CA` and `en_GB` use the `en_US` word pairs, which
have much more data than their own lists.

## Rebuilding

On the code branch:

    git clone https://codeberg.org/Helium314/aosp-dictionaries
    python3 scripts/build_bigrams.py aosp-dictionaries OUT_DIR

Then replace `v1/` on this branch with `OUT_DIR/v1/` and commit the updated
`res/values/bigrams.xml` on the code branch, or run the "Build word pairs"
workflow, which does both checks.
