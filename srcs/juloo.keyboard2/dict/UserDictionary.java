package juloo.keyboard2.dict;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Words added by the user. Matched in a case-insensitive way, suggested with
    the case they were added with.

    Stored in the credential protected storage because these words are
    personal. They are not available before the device is unlocked for the
    first time; loading is retried on every access until it succeeds. */
public final class UserDictionary
{
  public static UserDictionary instance(Context ctx)
  {
    if (_instance == null)
      _instance = new UserDictionary(ctx.getApplicationContext() != null ?
          ctx.getApplicationContext() : ctx);
    return _instance;
  }

  /** Return the word as it was added if [word] is in the dictionary, ignoring
      case. Return [null] otherwise. */
  public String find(String word)
  {
    if (!ensure_loaded())
      return null;
    return _words.get(key(word));
  }

  /** Words that start with [prefix] (ignoring case), excluding [prefix]
      itself. At most [max] results, in alphabetical order. */
  public List<String> prefix_matches(String prefix, int max)
  {
    List<String> res = new ArrayList<String>();
    if (max <= 0 || !ensure_loaded())
      return res;
    String k = key(prefix);
    for (Map.Entry<String, String> e :
        _words.subMap(k, false, k + Character.MAX_VALUE, false).entrySet())
    {
      if (res.size() >= max)
        break;
      res.add(e.getValue());
    }
    return res;
  }

  /** Returns [false] if the dictionary couldn't be loaded. */
  public boolean add(String word)
  {
    if (!ensure_loaded())
      return false;
    _words.put(key(word), word);
    save();
    return true;
  }

  public boolean remove(String word)
  {
    if (!ensure_loaded())
      return false;
    if (_words.remove(key(word)) == null)
      return false;
    save();
    return true;
  }

  /** Private */

  static UserDictionary _instance = null;

  static final String PREFS_NAME = "user_dictionary";
  static final String PREF_WORDS = "words";

  final Context _context;
  SharedPreferences _prefs = null;
  /** Lowercased word to the word as added by the user. */
  final TreeMap<String, String> _words = new TreeMap<String, String>();

  UserDictionary(Context ctx)
  {
    _context = ctx;
  }

  boolean ensure_loaded()
  {
    if (_prefs != null)
      return true;
    try
    {
      _prefs = _context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
      Set<String> words = _prefs.getStringSet(PREF_WORDS, null);
      if (words != null)
        for (String w : words)
          _words.put(key(w), w);
      return true;
    }
    catch (Exception e)
    {
      // Credential protected storage is locked during direct boot.
      _prefs = null;
      return false;
    }
  }

  void save()
  {
    _prefs.edit()
      .putStringSet(PREF_WORDS, new HashSet<String>(_words.values()))
      .apply();
  }

  static String key(String word)
  {
    return word.toLowerCase(Locale.ROOT);
  }
}
