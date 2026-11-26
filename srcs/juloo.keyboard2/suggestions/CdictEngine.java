package juloo.keyboard2.suggestions;

import juloo.cdict.Cdict;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;
import juloo.keyboard2.Config;
import juloo.keyboard2.dict.UserDictionary;

/** Engine backed by the installed [Cdict] dictionary and the user dictionary.
    It doesn't use the context and doesn't compute real scores: candidates are
    ranked by where they come from. */
public final class CdictEngine implements SuggestionEngine
{
  Config _config;
  UserDictionary _user_dict;

  public CdictEngine(Config conf, UserDictionary user_dict)
  {
    _config = conf;
    _user_dict = user_dict;
  }

  @Override
  public Candidates query(String[] context, String typed, int max)
  {
    Candidates c = new Candidates(max);
    // The current dictionary changes when the user switches dictionary.
    Cdict dict = _config.current_dictionary;
    String word = apply_substitutions(typed);
    // User words come first, they are more likely to be relevant.
    String user_exact = _user_dict.find(typed);
    add(c, user_exact);
    boolean found = (user_exact != null);
    Cdict.Result r = null;
    if (dict != null)
    {
      r = dict.find(word);
      if (r.found)
      {
        found = true;
        add(c, dict.word(r.index));
      }
    }
    for (String w : _user_dict.prefix_matches(typed, max))
      add(c, w);
    if (dict != null)
    {
      int[] suffixes = dict.suffixes(r, max);
      // Disable distance search for small words
      int[] dist = (word.length() < 3 || c.count + 1 >= max) ? NO_RESULTS :
        dict.distance(word, 1, max);
      for (int j = 0; j < max && !c.is_full(); j++)
      {
        if (suffixes.length > j)
          add(c, dict.word(suffixes[j]));
        if (dist.length > j)
          add(c, dict.word(dist[j]));
      }
    }
    c.typed_word_valid = found;
    return c;
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

  /** No real scores yet: each candidate scores lower than the previous one. */
  static void add(Candidates c, String w)
  {
    c.add(w, -c.count);
  }

  static final int[] NO_RESULTS = new int[0];
}
