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
import juloo.keyboard2.dict.UserDictionary;

/** Engine backed by the installed [Cdict] dictionary and the user dictionary.
    It doesn't use the context yet.

    Candidates are gathered from exact, prefix and distance lookups and ranked
    using the word frequency and the cost of the typo that would turn the
    word into what was typed ([TypoModel]). */
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
  TypoModel _typos = new TypoModel(KeyProximity.EMPTY);

  public CdictEngine(Config conf, final UserDictionary user_dict)
  {
    _config = conf;
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
    return query(_config.current_dictionary, _user_words, _typos, typed, max);
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

  /** [dict] might be [null]. */
  public static Candidates query(Cdict dict, UserWords user_words,
      TypoModel typos, String typed, int max)
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
    Collections.sort(pool, Scored.BEST_FIRST);
    for (Scored s : pool)
    {
      if (c.is_full())
        break;
      c.add(s.word, s.score);
    }
    return c;
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
