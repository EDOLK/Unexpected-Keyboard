package juloo.keyboard2.suggestions;

/** Source of word suggestions. [Suggestions] owns the state shown in the
    candidates view and delegates finding and ranking words to an engine, so
    that the engine can be replaced without touching the UI code.

    Engines are queried on the UI thread for every keystroke and must answer
    quickly. */
public interface SuggestionEngine
{
  /** Suggestions for the word being typed.
      [context] contains the words preceding the typed word, the most recent
      last. It might be empty, engines must not rely on it.
      [typed] is the word being typed, as it appears in the editor. It is
      never empty.
      Return at most [max] candidates, best first. */
  public Candidates query(String[] context, String typed, int max);

  public static final String[] NO_CONTEXT = new String[0];
}
