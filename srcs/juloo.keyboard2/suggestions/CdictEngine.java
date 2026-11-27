package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;
import juloo.keyboard2.Config;
import juloo.keyboard2.KeyboardData;
import juloo.keyboard2.dict.Bigrams;
import juloo.keyboard2.dict.UserBigrams;
import juloo.keyboard2.dict.UserDictionary;

/** Engine backed by the installed [Cdict] dictionary, the user dictionary,
    optionally word pairs ([Bigrams]) and the word pairs learned from the user
    ([UserBigrams]).

    Candidates are gathered from exact, prefix and distance lookups and ranked
    using the word frequency and the cost of the typo that would turn the
    word into what was typed ([TypoModel]). Words that often follow the
    previous word get a bonus. */
public final class CdictEngine implements SuggestionEngine
{
  /** Access to the user's words. */
  public static interface UserWords
  {
    /** The word as added by the user, ignoring case. [null] if not found. */
    public String find(String word);
    /** Words starting with [prefix], excluding [prefix] itself. */
    public List<String> prefix_matches(String prefix, int max);
  }

  Config _config;
  UserWords _user_words;
  UserBigrams _user_bigrams;
  TypoModel _typos = new TypoModel(KeyProximity.EMPTY);

  public CdictEngine(Config conf, final UserDictionary user_dict,
      UserBigrams user_bigrams)
  {
    _config = conf;
    _user_bigrams = user_bigrams;
    _user_words = new UserWords() {
      public String find(String w) { return user_dict.find(w); }
      public List<String> prefix_matches(String p, int max)
      { return user_dict.prefix_matches(p, max); }
    };
  }

  @Override
  public Candidates query(String[] context, String typed, int max)
  {
    // The current dictionary changes when the user switches dictionary.
    return query(_config.current_dictionary, _user_words, _typos,
        successors(context), _pairs, typed, max);
  }

  /** Words following any word, used to choose between splits of a word
      with missing spaces. */
  final Pairs _pairs = new Pairs() {
    public Bigrams.Successors successors(String prev)
    { return _user_bigrams.merge(_config.current_bigrams, prev); }
  };

  @Override
  public Candidates predict(String[] context, int max)
  {
    return predict(successors(context), max);
  }

  @Override
  public void learn(String[] context, String word)
  {
    if (context.length == 0)
      return;
    String prev = known_word(context[context.length - 1]);
    String next = known_word(word);
    if (prev != null && next != null)
      _user_bigrams.learn(prev, next);
  }

  Bigrams.Successors successors(String[] context)
  {
    if (context.length == 0)
      return Bigrams.NONE;
    return _user_bigrams.merge(_config.current_bigrams,
        context[context.length - 1]);
  }

  /** The word as spelled in the user or main dictionary, or [null] if it is
      unknown. Avoids learning typos. */
  String known_word(String w)
  {
    String u = _user_words.find(w);
    if (u != null)
      return u;
    Cdict dict = _config.current_dictionary;
    if (dict == null)
      return null;
    Cdict.Result r = dict.find(apply_substitutions(w));
    return r.found ? dict.word(r.index) : null;
  }

  @Override
  public void set_layout(KeyboardData kw)
  {
    _typos = new TypoModel(KeyProximity.of_layout(kw));
  }

  /** Frequency given to user words. Higher than any dictionary word. */
  static final int USER_WORD_FREQ = 16;
  /** Weight of the typo cost relative to the frequency: one full edit is
      worth 3 levels of frequency (out of 16). Tuned with
      [scripts/eval_suggestions]. */
  static final float COST_WEIGHT = 3.f;
  /** Number of words requested to each lookup. */
  static final int POOL_SIZE = 16;
  /** Bonus, in levels of frequency, for a following word of weight
      [Bigrams.MAX_WEIGHT]. Weights are normalized by [UserBigrams.merge]:
      the most likely pair from the word pairs file gets a bonus of about 1
      and learned pairs get up to 3. Tuned with [scripts/eval_suggestions]. */
  static final float BIGRAM_BONUS = 3.f;
  /** Number of splits of a word with missing spaces that are suggested. */
  static final int SPLITS = 2;
  /** Frequency of a following word that is not in the dictionary. */
  static final int UNKNOWN_SUCCESSOR_FREQ = 8;

  /** Words following the last word of [context] according to [bigrams]
      only, with weights normalized like [UserBigrams.merge]. [bigrams] might
      be [null]. */
  public static Bigrams.Successors successors(Bigrams bigrams,
      String[] context)
  {
    if (bigrams == null || context.length == 0)
      return Bigrams.NONE;
    return UserBigrams.merge(bigrams, new java.util.ArrayList<UserBigrams.Pair>(),
        context[context.length - 1]);
  }

  /** Words following a given word. */
  public static interface Pairs
  {
    /** Never [null]. */
    public Bigrams.Successors successors(String prev);
  }

  public static final Pairs NO_PAIRS = new Pairs() {
    public Bigrams.Successors successors(String prev) { return Bigrams.NONE; }
  };

  /** Pairs from [bigrams] only, normalized like [UserBigrams.merge].
      [bigrams] might be [null]. */
  public static Pairs pairs_of(final Bigrams bigrams)
  {
    return new Pairs() {
      public Bigrams.Successors successors(String prev)
      { return CdictEngine.successors(bigrams, new String[]{ prev }); }
    };
  }

  public static Candidates query(Cdict dict, UserWords user_words,
      TypoModel typos, Bigrams.Successors succ, String typed, int max)
  {
    return query(dict, user_words, typos, succ, NO_PAIRS, typed, max);
  }

  /** [dict] might be [null]. [succ] are the words following the previous
      word, see [successors]. [pairs] is used to split words with missing
      spaces. */
  public static Candidates query(Cdict dict, UserWords user_words,
      TypoModel typos, Bigrams.Successors succ, Pairs pairs, String typed,
      int max)
  {
    Candidates c = new Candidates(max);
    String word = apply_substitutions(typed);
    // The typed word, if valid, comes first so that it is not replaced on
    // space. User words come first, they are more likely to be relevant.
    String user_exact = user_words.find(typed);
    c.add(user_exact, Float.MAX_VALUE);
    boolean found = (user_exact != null);
    Cdict.Result r = null;
    if (dict != null)
    {
      r = dict.find(word);
      if (r.found)
      {
        found = true;
        c.add(dict.word(r.index), Float.MAX_VALUE);
      }
    }
    c.typed_word_valid = found;
    // Gather more candidates than needed and rank them.
    List<Scored> pool = new ArrayList<Scored>();
    for (String w : user_words.prefix_matches(typed, POOL_SIZE))
      pool.add(score(typos, typed, w, USER_WORD_FREQ));
    if (dict != null)
    {
      for (int i : dict.suffixes(r, POOL_SIZE))
        pool.add(score(typos, typed, dict, i));
      // Disable distance search for small words
      if (word.length() >= 3)
      {
        for (int i : dict.distance(word, 1, POOL_SIZE))
          pool.add(score(typos, typed, dict, i));
        add_transpositions(pool, dict, typos, typed, word);
      }
    }
    if (succ.words.length > 0)
    {
      // Following words might not be among the most frequent words found
      // above.
      for (String w : succ.words)
        pool.add(score(typos, typed, w, freq_of(dict, w)));
      apply_bigram_bonus(pool, succ);
    }
    // The typed word might be several words with missing spaces.
    if (!found && dict != null)
      for (WordSplitter.Split s : WordSplitter.split(
            lexicon(dict, user_words, pairs), succ, typed, SPLITS))
        pool.add(new Scored(s.text(), s.score));
    Collections.sort(pool, Scored.BEST_FIRST);
    for (Scored s : pool)
    {
      if (c.is_full())
        break;
      c.add(s.word, s.score);
    }
    return c;
  }

  /** Words following the previous word, most likely first. */
  public static Candidates predict(Bigrams.Successors succ, int max)
  {
    Candidates c = new Candidates(max);
    c.typed_word_valid = true;
    // Successors are sorted by decreasing weight.
    for (int i = 0; i < succ.words.length && !c.is_full(); i++)
      c.add(succ.words[i], succ.weights[i]);
    return c;
  }

  static void apply_bigram_bonus(List<Scored> pool, Bigrams.Successors succ)
  {
    for (int i = 0; i < pool.size(); i++)
    {
      Scored s = pool.get(i);
      for (int j = 0; j < succ.words.length; j++)
        if (succ.words[j].equalsIgnoreCase(s.word))
        {
          float bonus = BIGRAM_BONUS * succ.weights[j] / Bigrams.MAX_WEIGHT;
          pool.set(i, new Scored(s.word, s.score + bonus));
          break;
        }
    }
  }

  /** Lookups for [WordSplitter]. */
  static WordSplitter.Lexicon lexicon(final Cdict dict,
      final UserWords user_words, final Pairs pairs)
  {
    return new WordSplitter.Lexicon() {
      public WordSplitter.Word find(String w)
      {
        String u = user_words.find(w);
        if (u != null)
          return new WordSplitter.Word(u, USER_WORD_FREQ);
        Cdict.Result r = dict.find(apply_substitutions(w));
        if (!r.found)
          return null;
        return new WordSplitter.Word(dict.word(r.index), dict.freq(r.index));
      }

      public List<WordSplitter.Word> completions(String prefix, int max)
      {
        List<WordSplitter.Word> ws = new ArrayList<WordSplitter.Word>();
        for (String u : user_words.prefix_matches(prefix, max))
          ws.add(new WordSplitter.Word(u, USER_WORD_FREQ));
        Cdict.Result r = dict.find(apply_substitutions(prefix));
        for (int i : dict.suffixes(r, max))
          ws.add(new WordSplitter.Word(dict.word(i), dict.freq(i)));
        return ws;
      }

      public Bigrams.Successors successors(String prev)
      { return pairs.successors(prev); }
    };
  }

  static int freq_of(Cdict dict, String w)
  {
    if (dict == null)
      return UNKNOWN_SUCCESSOR_FREQ;
    Cdict.Result r = dict.find(apply_substitutions(w));
    return r.found ? dict.freq(r.index) : UNKNOWN_SUCCESSOR_FREQ;
  }

  /** Swapped letters are two edits away and are not found by
      [Cdict.distance]. Lookup every word made by swapping two consecutive
      chars. */
  static void add_transpositions(List<Scored> pool, Cdict dict,
      TypoModel typos, String typed, String word)
  {
    char[] cs = word.toCharArray();
    for (int i = 0; i + 1 < cs.length; i++)
    {
      char a = cs[i], b = cs[i + 1];
      if (a == b || Character.isSurrogate(a) || Character.isSurrogate(b))
        continue;
      cs[i] = b; cs[i + 1] = a;
      Cdict.Result r = dict.find(new String(cs));
      if (r.found)
        pool.add(score(typos, typed, dict, r.index));
      cs[i] = a; cs[i + 1] = b;
    }
  }

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  public static String apply_substitutions(String w)
  {
    StringBuilder b = new StringBuilder(w);
    int len = w.length();
    for (int i = 0; i < len; i++)
    {
      char r =
        ComposeKey.transform_char(ComposeKeyData.substitutions, b.charAt(i));
      if (r != 0) b.setCharAt(i, r);
    }
    return b.toString();
  }

  static final class Scored
  {
    final String word;
    final float score;
    Scored(String w, float s) { word = w; score = s; }

    static final Comparator<Scored> BEST_FIRST = new Comparator<Scored>() {
      public int compare(Scored a, Scored b)
      { return Float.compare(b.score, a.score); }
    };
  }

  static Scored score(TypoModel typos, String typed, Cdict dict, int index)
  {
    return score(typos, typed, dict.word(index), dict.freq(index));
  }

  static Scored score(TypoModel typos, String typed, String w, int freq)
  {
    return new Scored(w, freq - COST_WEIGHT * typos.cost(typed, w));
  }
}
