package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.UserDictionary;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  UserDictionary _user_dict;
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
    _callback = c;
    _config = conf;
    _user_dict = user_dict;
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
    clear();
    Cdict dict = _config.current_dictionary;
    String typed = word;
    boolean first_char_upper = Character.isUpperCase(word.charAt(0));
    word = apply_substitutions(word);
    // User words come first, they are more likely to be relevant.
    String user_exact = _user_dict.find(typed);
    add_suggestion(user_exact);
    boolean found = (user_exact != null);
    Cdict.Result r = null;
    if (dict != null)
    {
      r = dict.find(word);
      if (r.found)
      {
        found = true;
        add_suggestion(dict.word(r.index));
      }
    }
    for (String w : _user_dict.prefix_matches(typed, MAX_COUNT))
      add_suggestion(w);
    if (dict != null)
    {
      int[] suffixes = dict.suffixes(r, MAX_COUNT);
      // Disable distance search for small words
      int[] dist = (word.length() < 3 || count + 1 >= MAX_COUNT) ? NO_RESULTS :
        dict.distance(word, 1, MAX_COUNT);
      for (int j = 0; j < MAX_COUNT && count < MAX_COUNT; j++)
      {
        if (suffixes.length > j)
          add_suggestion(dict.word(suffixes[j]));
        if (dist.length > j)
          add_suggestion(dict.word(dist[j]));
      }
    }
    if (first_char_upper)
      capitalize_results(suggestions, count);
    // Show the typed word when it is unknown so that it can be added to the
    // user dictionary. It takes the last slot.
    if (!found)
    {
      unknown_word = typed;
      if (count < MAX_COUNT)
        count++;
      suggestions[count - 1] = typed;
    }
    emoji_suggestion = query_emoji(word); // word with substitutions applied
    return count;
  }

  /** Append a suggestion, unless it is [null], already present or there is no
      more room. */
  void add_suggestion(String w)
  {
    if (w == null || count >= MAX_COUNT)
      return;
    for (int i = 0; i < count; i++)
      if (suggestions[i].equalsIgnoreCase(w))
        return;
    suggestions[count++] = w;
  }

  static void capitalize_results(String[] s, int count)
  {
    for (int i = 0; i < count; i++)
      s[i] = s[i].substring(0, 1).toUpperCase() + s[i].substring(1);
  }

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

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  String apply_substitutions(String w)
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

  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
