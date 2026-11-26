#!/usr/bin/env bash
# Offline evaluation of the suggestion ranking against a real dictionary.
# Compares the ranking from before the typo model (copied in Eval.java) with
# the current [CdictEngine] on simulated typos and on prefix completion.
#
# The word pairs are built from the AOSP word list with gen_bigrams.py and
# evaluated on public domain books from Project Gutenberg.
#
# Usage: scripts/eval_suggestions/run.sh [comma,separated,queries]
# Set DEBUG=1 to list prefix completion regressions.
# Requires: a JDK, a C compiler and the app classes built with
#   ./gradlew compileDebugJavaWithJavac
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE=$ROOT/scripts/eval_suggestions
OUT=$ROOT/build/eval_suggestions
mkdir -p "$OUT/native" "$OUT/classes"

DICT_URL=https://raw.githubusercontent.com/Julow/Unexpected-Keyboard-dictionaries/refs/heads/main/v1/en_US.dict
WORDS_URL=https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt
[ -f "$OUT/en_US.dict" ] || curl -sSfL "$DICT_URL" | gunzip > "$OUT/en_US.dict"
[ -f "$OUT/en_50k.txt" ] || curl -sSfL -o "$OUT/en_50k.txt" "$WORDS_URL"
WORDLIST_URL=https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists/main_en_US.combined.gz
[ -f "$OUT/main_en_US.combined.gz" ] || curl -sSfL -o "$OUT/main_en_US.combined.gz" "$WORDLIST_URL"
[ -f "$OUT/en_US.bigrams" ] || python3 "$ROOT/scripts/gen_bigrams.py" en_US "$OUT/main_en_US.combined.gz" "$OUT/en_US.bigrams"
CORPUS=""
for book in Pride-and-Prejudice_1342/master/1342 The-Adventures-of-Sherlock-Holmes_1661/master/1661; do
  f=$OUT/${book##*/}.txt
  [ -f "$f" ] || curl -sSfL -o "$f" "https://raw.githubusercontent.com/GITenberg/$book.txt"
  CORPUS=$CORPUS${CORPUS:+,}$f
done

# Native library for the host.
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
CDICT=$ROOT/vendor/cdict
if [ ! -f "$OUT/native/libcdict_java.so" ]; then
  cc -O2 -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$CDICT/libcdict" "$CDICT/libcdict/libcdict.c" \
    "$CDICT/java/jni/juloo_cdict_Cdict.c" -o "$OUT/native/libcdict_java.so"
fi

SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")}
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
CP=$ROOT/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$ANDROID_JAR
javac -nowarn -d "$OUT/classes" -cp "$CP" "$HERE/Eval.java"
java -Djava.library.path="$OUT/native" -cp "$OUT/classes:$CP" \
  juloo.keyboard2.suggestions.Eval "$OUT/en_US.dict" "$OUT/en_50k.txt" \
  --bigrams "$OUT/en_US.bigrams" --corpus "$CORPUS" "$@"
