package juloo.keyboard2.suggestions;

/** Estimate how likely it is that [typed] was typed when the user meant
    [word], or a word that [word] completes. The result is a cost in "edits",
    [0] meaning that [typed] is exactly [word].

    This is a weighted Damerau-Levenshtein distance where substitutions are
    weighted using the layout ([KeyProximity]) and some common mistakes are
    cheaper. */
public final class TypoModel
{
  /** Typing an extra char or missing a char. */
  public static final float EXTRA_CHAR = 1.f;
  /** Swapping two consecutive chars. */
  public static final float TRANSPOSE = 0.7f;
  /** Doubling a char or missing a double char, eg. "helo" for "hello". */
  public static final float DOUBLE = 0.5f;
  /** Adding or missing an apostrophe, eg. "dont" for "don't". */
  public static final float APOSTROPHE = 0.3f;
  /** [word] is longer than [typed] and might be completing it. Must be lower
      than the cost of a typo, the user is more likely to be typing the
      beginning of a word than to have made a mistake. */
  public static final float COMPLETION = 0.5f;

  KeyProximity _prox;

  public TypoModel(KeyProximity prox)
  {
    _prox = prox;
  }

  /** The cost of typing [typed] for [word] or a completion of it. */
  public float cost(String typed, String word)
  {
    int[] t = normalize(typed);
    int[] w = normalize(word);
    int n = t.length;
    int m = w.length;
    float[][] d = new float[n + 1][m + 1];
    d[0][0] = 0.f;
    for (int i = 1; i <= n; i++)
      d[i][0] = d[i - 1][0] + extra_char_cost(t, i - 1);
    for (int j = 1; j <= m; j++)
      d[0][j] = d[0][j - 1] + extra_char_cost(w, j - 1);
    for (int i = 1; i <= n; i++)
      for (int j = 1; j <= m; j++)
      {
        float c = d[i - 1][j - 1] + _prox.substitution_cost(t[i - 1], w[j - 1]);
        c = Math.min(c, d[i - 1][j] + extra_char_cost(t, i - 1));
        c = Math.min(c, d[i][j - 1] + extra_char_cost(w, j - 1));
        if (i >= 2 && j >= 2 && t[i - 1] == w[j - 2] && t[i - 2] == w[j - 1])
          c = Math.min(c, d[i - 2][j - 2] + TRANSPOSE);
        d[i][j] = c;
      }
    float best = d[n][m];
    // [typed] matches a prefix of [word].
    for (int j = 1; j < m; j++)
      best = Math.min(best, d[n][j] + COMPLETION);
    return best;
  }

  /** Cost of [s[i]] being in [s] and missing from the other string. Used for
      both insertions and deletions. */
  static float extra_char_cost(int[] s, int i)
  {
    if (s[i] == '\'')
      return APOSTROPHE;
    if (i > 0 && s[i] == s[i - 1])
      return DOUBLE;
    return EXTRA_CHAR;
  }

  static int[] normalize(String s)
  {
    int[] cs = new int[s.codePointCount(0, s.length())];
    for (int i = 0, j = 0; i < s.length(); j++)
    {
      int c = s.codePointAt(i);
      cs[j] = KeyProximity.normalize(c);
      i += Character.charCount(c);
    }
    return cs;
  }
}
