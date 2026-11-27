package juloo.keyboard2.suggestions;

/** Result of [SuggestionEngine.query]. Words are ordered best first. */
public final class Candidates
{
  /** The first [count] elements are not [null]. */
  public final String[] words;
  /** Score of each word in [words], higher is better. Scores are only
      comparable within the same result. */
  public final float[] scores;
  public int count = 0;
  /** Whether the typed word is a known word. When [false], the typed word is
      offered so that it can be added to the user dictionary. */
  public boolean typed_word_valid = false;

  public Candidates(int max)
  {
    words = new String[max];
    scores = new float[max];
  }

  public boolean is_full() { return count >= words.length; }

  /** Append a word, unless it is [null], already present (ignoring case) or
      there is no more room. Words must be added in decreasing order of
      [score]. Return whether the word was added. */
  public boolean add(String w, float score)
  {
    if (w == null || is_full())
      return false;
    for (int i = 0; i < count; i++)
      if (words[i].equalsIgnoreCase(w))
        return false;
    words[count] = w;
    scores[count] = score;
    count++;
    return true;
  }
}
