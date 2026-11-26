package juloo.keyboard2.suggestions;

import juloo.cdict.Cdict;
import juloo.keyboard2.dict.UserDictionary;
import juloo.keyboard2.Config;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. Finding and ranking words is done by a
    [SuggestionEngine]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  UserDictionary _user_dict;
  SuggestionEngine _engine;
  boolean _enabled;
  /** The word passed to the last call to [currently_typed_word]. */
  String _last_typed_word = "";

  /** Current suggestions. The best suggestion is at index [0]. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  /** The word being typed when it is in no dictionary. It is then also one of
      the [suggestions], so that it can be added to the user dictionary.
      [null] otherwise. */
  public String unknown_word = null;
  /** Number of suggestions in [suggestions]. */
  public static final int MAX_COUNT = 3;

  public Suggestions(Callback c, Config conf, UserDictionary user_dict)
  {
    this(c, conf, user_dict, new CdictEngine(conf, user_dict));
  }

  public Suggestions(Callback c, Config conf, UserDictionary user_dict,
      SuggestionEngine engine)
  {
    _callback = c;
    _config = conf;
    _user_dict = user_dict;
    _engine = engine;
  }

  public void started()
  {
    _enabled = _config.editor_config.should_show_candidates_view;
    clear();
  }

  public void currently_typed_word(String word)
  {
    if (!_enabled)
      return;
    _last_typed_word = word;
    if (word.length() < 2)
      clear();
    else
      query_suggestions(word);
    _callback.set_suggestions(this);
  }

  /** Result of [toggle_user_word]. */
  public static enum UserWordChange { ADDED, REMOVED, NONE }

  /** Called when a suggestion is long pressed. Add [word] to the user
      dictionary if it is the unknown word being typed or remove it if it is
      a user word. Suggestions are refreshed. */
  public UserWordChange toggle_user_word(String word)
  {
    UserWordChange r = UserWordChange.NONE;
    if (word.equals(unknown_word))
    {
      if (_user_dict.add(word))
        r = UserWordChange.ADDED;
    }
    else
    {
      String w = _user_dict.find(word);
      if (w != null && _user_dict.remove(w))
        r = UserWordChange.REMOVED;
    }
    if (r != UserWordChange.NONE)
      currently_typed_word(_last_typed_word);
    return r;
  }

  void clear()
  {
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
      suggestions[i] = null;
    emoji_suggestion = null;
    unknown_word = null;
  }

  int query_suggestions(String word)
  {
    Candidates c =
      _engine.query(SuggestionEngine.NO_CONTEXT, word, MAX_COUNT);
    set_candidates(c, word);
    // Word with substitutions applied
    emoji_suggestion = query_emoji(CdictEngine.apply_substitutions(word));
    return count;
  }

  /** Fill [suggestions] from the engine's result. [typed] is the word being
      typed. */
  void set_candidates(Candidates c, String typed)
  {
    clear();
    for (int i = 0; i < c.count && count < MAX_COUNT; i++)
      suggestions[count++] = c.words[i];
    if (Character.isUpperCase(typed.charAt(0)))
      capitalize_results(suggestions, count);
    // Show the typed word when it is unknown so that it can be added to the
    // user dictionary. It takes the last slot.
    if (!c.typed_word_valid)
    {
      unknown_word = typed;
      if (count < MAX_COUNT)
        count++;
      suggestions[count - 1] = typed;
    }
  }

  static void capitalize_results(String[] s, int count)
  {
    for (int i = 0; i < count; i++)
      s[i] = s[i].substring(0, 1).toUpperCase() + s[i].substring(1);
  }

  /** [word] must have substitutions applied. */
  String query_emoji(String word)
  {
    Cdict dict = _config.emoji_dictionary;
    // Disable emoji suggestion for short words
    if (dict == null || word.length() < 3)
      return null;
    Cdict.Result r = dict.find(word);
    if (r.found)
      return dict.word(r.index);
    int[] s = dict.suffixes(r, 1);
    if (s.length > 0)
      return dict.word(s[0]);
    return null;
  }

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
