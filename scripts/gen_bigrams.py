#!/usr/bin/env python3
"""Build a word-pair (bigram) file for Unexpected Keyboard from an AOSP word
list (.combined or .combined.gz).

Usage: gen_bigrams.py DICT_NAME INPUT.combined[.gz] OUTPUT.bigrams

DICT_NAME is the name of the dictionary the file is meant for, eg. "en_US". It
is stored in the file and used to install it next to the right dictionary.

The format is read by srcs/juloo.keyboard2/dict/Bigrams.java. All integers are
big endian.

  magic     4 bytes  "UKBG"
  version   u8       1
  reserved  3 bytes  0
  name_len  u16, followed by the dictionary name in UTF-8
  n_words   u32
  word_off  u32[n_words + 1]  Offsets into [strings]. Words are sorted by
                              their UTF-8 bytes and unique.
  succ_off  u32[n_words + 1]  Offsets into [succ], in entries. The words
                              following word i are succ[succ_off[i]] to
                              succ[succ_off[i + 1] - 1].
  str_len   u32, followed by [strings], the UTF-8 encoded words concatenated
  n_succ    u32, followed by [succ], n_succ entries of 4 bytes:
              u24 word index, u8 weight (1 to 255, higher is more likely)
                Entries of a word are sorted by decreasing weight.

AOSP word lists list up to 3 following words per word with "f=1" to "f=3",
"3" being the most likely, which are used directly as weights.
"""

import gzip
import re
import struct
import sys

MAGIC = b"UKBG"
VERSION = 1

LINE_RE = re.compile(r"^\s*(word|bigram)=([^,]*)(.*)$")
FIELD_RE = re.compile(r",\s*([a-zA-Z_]+)=([^,]*)")


def parse_combined(path):
  """Return a dict: word -> list of (next word, weight)."""
  opener = gzip.open if path.endswith(".gz") else open
  pairs = {}
  current = None
  with opener(path, "rt", encoding="utf-8") as f:
    for line in f:
      m = LINE_RE.match(line)
      if m is None:
        continue
      kind, value, rest = m.groups()
      fields = dict(FIELD_RE.findall(rest))
      if kind == "word":
        current = value
      elif current is not None:
        weight = int(fields.get("f", "1"))
        weight = max(1, min(weight, 255))
        pairs.setdefault(current, {})
        prev = pairs[current].get(value, 0)
        pairs[current][value] = max(prev, weight)
  return {w: sorted(succ.items(), key=lambda kv: (-kv[1], kv[0]))
          for w, succ in pairs.items() if succ}


def write_bigrams(dict_name, pairs, out_path):
  words = set(pairs)
  for succ in pairs.values():
    words.update(w for w, _ in succ)
  encoded = sorted(w.encode("utf-8") for w in words)
  if len(encoded) >= (1 << 24):
    raise ValueError("Too many words")
  index = {w: i for i, w in enumerate(encoded)}
  word_off = [0]
  for w in encoded:
    word_off.append(word_off[-1] + len(w))
  succ_off = [0]
  succ = bytearray()
  for w in encoded:
    entries = pairs.get(w.decode("utf-8"), [])
    for next_w, weight in entries:
      succ += struct.pack(">I", index[next_w.encode("utf-8")])[1:]
      succ.append(weight)
    succ_off.append(succ_off[-1] + len(entries))
  name = dict_name.encode("utf-8")
  with open(out_path, "wb") as out:
    out.write(MAGIC)
    out.write(struct.pack(">B3x", VERSION))
    out.write(struct.pack(">H", len(name)))
    out.write(name)
    out.write(struct.pack(">I", len(encoded)))
    out.write(struct.pack(">%dI" % len(word_off), *word_off))
    out.write(struct.pack(">%dI" % len(succ_off), *succ_off))
    out.write(struct.pack(">I", word_off[-1]))
    for w in encoded:
      out.write(w)
    out.write(struct.pack(">I", succ_off[-1]))
    out.write(succ)
  return len(encoded), succ_off[-1]


def main():
  if len(sys.argv) != 4:
    sys.exit(__doc__.split("\n\n")[1])
  dict_name, inp, out = sys.argv[1:]
  pairs = parse_combined(inp)
  n_words, n_succ = write_bigrams(dict_name, pairs, out)
  print("%s: %d words, %d pairs" % (out, n_words, n_succ))


if __name__ == "__main__":
  main()
