package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import juloo.keyboard2.dict.Bigrams;

/** Find the words in a typed word with missing spaces, eg. "itsmin" for
    "it's mine".

    The typed word is cut into known words with a dynamic programming search
    over the positions where a space might be missing, keeping the best few
    splits at each position. The last piece is still being typed and can be
    completed. A missing apostrophe before a contraction suffix is fixed.
    Other typos are not corrected, the search would be too large.

    Splits are scored on the same scale as single words in [CdictEngine]:
    the frequencies of the words are added, every extra word costs
    [SPLIT_COST], typos cost [CdictEngine.COST_WEIGHT] per edit, and words
    that often follow each other get a bonus. */
public final class WordSplitter
{
  /** Dictionary access. */
  public static interface Lexicon
  {
    /** The word as spelled in the dictionary, or [null] if unknown. */
    public Word find(String w);
    /** Most likely words starting with [prefix], excluding [prefix]. */
    public List<Word> completions(String prefix, int max);
    /** Words likely to follow [prev]. Never [null]. */
    public Bigrams.Successors successors(String prev);
  }

  public static final class Word
  {
    public final String word;
    /** From [0] to [CdictEngine.USER_WORD_FREQ]. */
    public final int freq;
    public Word(String w, int f) { word = w; freq = f; }
  }

  public static final class Split
  {
    public final String[] words;
    public final float score;
    Split(String[] w, float s) { words = w; score = s; }
    /** The words separated by spaces. */
    public String text()
    {
      StringBuilder b = new StringBuilder(words[0]);
      for (int i = 1; i < words.length; i++)
        b.append(' ').append(words[i]);
      return b.toString();
    }
  }

  /** Only words at least this long are split. */
  public static final int MIN_LENGTH = 4;
  /** Splits have at most this many words. */
  public static final int MAX_WORDS = 4;
  /** Longest piece looked up. */
  static final int MAX_PIECE = 16;
  /** Splits kept at each position during the search. */
  static final int BEAM = 6;
  /** Cost of each extra word, in levels of frequency. Dictionary
      frequencies are compressed (most words are between 11 and 15), so this
      is higher than the frequency of most words: splitting into more words
      must be supported by word pairs. Tuned with [scripts/eval_suggestions]
      (lower suggests splits for more typos, higher loses splits to
      corrections into a single word). */
  static final float SPLIT_COST = 16.f;
  /** Minimum frequency of a piece, by length. Short dictionary entries are
      very often abbreviations or parts of other words ("ts", "ng"). */
  static final int MIN_FREQ_ONE_LETTER = 14;
  static final int MIN_FREQ_TWO_LETTERS = 13;
  static final int MIN_FREQ = 8;
  /** Bonus, in levels of frequency, for a pair of words of weight
      [Bigrams.MAX_WEIGHT]. Larger than [CdictEngine.BIGRAM_BONUS] because
      pairs are a strong signal to choose between splits. */
  static final float PAIR_BONUS = 6.f;
  /** Completions requested for the last piece. */
  static final int COMPLETIONS = 3;
  /** Shortest last piece that is completed. Completing 2 letters helps
      while typing the last word but suggests more splits for typos. */
  static final int MIN_COMPLETED = 2;
  /** Complete splits rescored with word pairs. */
  static final int RESCORED = 12;

  final Lexicon _lex;
  /** Cache of lookups, by piece. */
  final Map<String, List<Choice>> _pieces = new HashMap<String, List<Choice>>();
  /** Cache of [Lexicon.successors]. */
  final Map<String, Bigrams.Successors> _succ =
    new HashMap<String, Bigrams.Successors>();

  public WordSplitter(Lexicon lex)
  {
    _lex = lex;
  }

  /** Best splits of [typed] into 2 or more words, best first. [succ] are
      the words likely to follow the previous word. Returns an empty list if
      [typed] cannot be split. */
  public static List<Split> split(Lexicon lex, Bigrams.Successors succ,
      String typed, int max)
  {
    return new WordSplitter(lex).split(succ, typed, max);
  }

  public List<Split> split(Bigrams.Successors succ, String typed, int max)
  {
    List<Split> res = new ArrayList<Split>();
    int n = typed.length();
    if (n < MIN_LENGTH || !splittable(typed))
      return res;
    // [beams.get(i)] contains the best splits of [typed[0..i]].
    List<List<State>> beams = new ArrayList<List<State>>(n + 1);
    for (int i = 0; i <= n; i++)
      beams.add(new ArrayList<State>());
    beams.get(0).add(new State(null, null, 0, 0.f));
    for (int i = 0; i < n; i++)
    {
      List<State> from = beams.get(i);
      if (from.isEmpty())
        continue;
      Collections.sort(from, State.BEST_FIRST);
      if (from.size() > BEAM)
        from.subList(BEAM, from.size()).clear();
      for (int j = i + 1; j <= n && j - i <= MAX_PIECE; j++)
      {
        boolean last = (j == n);
        for (Choice c : choices(typed.substring(i, j), last))
          for (State s : from)
            if (s.count < MAX_WORDS)
              beams.get(j).add(s.then(c));
      }
    }
    // Rescore the complete splits with word pairs, which is too costly to do
    // during the search.
    List<State> complete = beams.get(n);
    Collections.sort(complete, State.BEST_FIRST);
    List<Split> all = new ArrayList<Split>();
    for (State s : complete)
    {
      if (all.size() >= RESCORED)
        break;
      if (s.count < 2)
        continue;
      String[] words = s.words();
      float score = s.score - SPLIT_COST * (s.count - 1)
        + pair_bonus(succ, words[0]);
      for (int k = 0; k + 1 < words.length; k++)
        score += pair_bonus(successors(words[k]), words[k + 1]);
      all.add(new Split(words, score));
    }
    Collections.sort(all, SPLIT_BEST_FIRST);
    for (Split s : all)
    {
      if (res.size() >= max)
        break;
      boolean dup = false;
      for (Split r : res)
        dup |= r.text().equalsIgnoreCase(s.text());
      if (!dup)
        res.add(s);
    }
    return res;
  }

  Bigrams.Successors successors(String w)
  {
    Bigrams.Successors s = _succ.get(w);
    if (s == null)
    {
      s = _lex.successors(w);
      _succ.put(w, s);
    }
    return s;
  }

  /** Bonus for [next] following a word whose successors are [succ]. */
  static float pair_bonus(Bigrams.Successors succ, String next)
  {
    for (int i = 0; i < succ.words.length; i++)
      if (succ.words[i].equalsIgnoreCase(next))
        return PAIR_BONUS * succ.weights[i] / Bigrams.MAX_WEIGHT;
    return 0.f;
  }

  /** Only letters and apostrophes. */
  static boolean splittable(String s)
  {
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (!Character.isLetter(c) && c != '\'')
        return false;
    }
    return true;
  }

  /** Contraction suffixes after which an apostrophe might be missing. */
  static final String[] CONTRACTIONS = { "s", "t", "m", "d", "re", "ve", "ll" };

  /** Words that [piece] might be. [last] is whether this is the last piece,
      which can be completed. */
  List<Choice> choices(String piece, boolean last)
  {
    String key = last ? piece + "\u0000" : piece;
    List<Choice> cs = _pieces.get(key);
    if (cs != null)
      return cs;
    cs = new ArrayList<Choice>();
    add_word(cs, piece, _lex.find(piece), 0.f);
    if (piece.indexOf('\'') < 0)
      for (String suffix : CONTRACTIONS)
      {
        int k = piece.length() - suffix.length();
        if (k >= 1 && piece.endsWith(suffix))
          add_word(cs, piece,
              _lex.find(piece.substring(0, k) + "'" + suffix),
              TypoModel.APOSTROPHE);
      }
    if (last && piece.length() >= MIN_COMPLETED)
      for (Word w : _lex.completions(piece, COMPLETIONS))
        add_word(cs, piece, w, TypoModel.COMPLETION);
    _pieces.put(key, cs);
    return cs;
  }

  static void add_word(List<Choice> cs, String piece, Word w, float edits)
  {
    if (w == null || !case_matches(piece, w.word))
      return;
    int len = w.word.length();
    int min_freq = (len == 1) ? MIN_FREQ_ONE_LETTER :
      (len == 2) ? MIN_FREQ_TWO_LETTERS : MIN_FREQ;
    if (w.freq < min_freq)
      return;
    cs.add(new Choice(w.word,
          w.freq - CdictEngine.COST_WEIGHT * edits));
  }

  /** Abbreviations and names are only used when typed with their capital
      letters, they are otherwise likely to be parts of other words ("TT"
      in "thatthat"). "I" and its contractions are always allowed. */
  static boolean case_matches(String piece, String word)
  {
    if (word.equals("I") || word.startsWith("I'"))
      return true;
    String p = piece.replace("'", "");
    String w = word.replace("'", "");
    for (int i = 0; i < w.length(); i++)
      if (Character.isUpperCase(w.charAt(i))
          && (i >= p.length() || !Character.isUpperCase(p.charAt(i))))
        return false;
    return true;
  }

  static final class Choice
  {
    final String word;
    final float score;
    Choice(String w, float s) { word = w; score = s; }
  }

  /** A partial split, as a linked list of words, the last word first. */
  static final class State
  {
    final State prev;
    final String word;
    final int count;
    final float score;

    State(State p, String w, int c, float s)
    { prev = p; word = w; count = c; score = s; }

    State then(Choice c)
    { return new State(this, c.word, count + 1, score + c.score); }

    String[] words()
    {
      String[] ws = new String[count];
      State s = this;
      for (int i = count - 1; i >= 0; i--, s = s.prev)
        ws[i] = s.word;
      return ws;
    }

    /** The beam keeps the best splits per number of words, otherwise splits
        into many short words would never be compared with fewer words. */
    static final Comparator<State> BEST_FIRST = new Comparator<State>() {
      public int compare(State a, State b)
      {
        float sa = a.score - SPLIT_COST * a.count;
        float sb = b.score - SPLIT_COST * b.count;
        return Float.compare(sb, sa);
      }
    };
  }

  static final Comparator<Split> SPLIT_BEST_FIRST = new Comparator<Split>() {
    public int compare(Split a, Split b)
    { return Float.compare(b.score, a.score); }
  };
}
