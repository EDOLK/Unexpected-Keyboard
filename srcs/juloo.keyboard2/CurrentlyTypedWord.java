package juloo.keyboard2;

import android.os.Build.VERSION;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import java.util.List;

/** Keep track of the word being typed. This also tracks whether the selection
    is empty. */
public final class CurrentlyTypedWord
{
  InputConnection _ic = null;
  Handler _handler;
  Callback _callback;

  /** The currently typed word. */
  StringBuilder _w = new StringBuilder();
  /** Text before the currently typed word, used to compute [context()]. At
      most [CONTEXT_CHARS] long. */
  StringBuilder _before = new StringBuilder();
  /** Whether the beginning of [_before] might be in the middle of a word. */
  boolean _before_truncated = false;
  /** This can be disabled if the editor doesn't support looking at the text
      before the cursor. */
  boolean _enabled = false;
  /** The current word is empty while the selection is ongoing. */
  boolean _has_selection = false;
  /** Used to avoid concurrent refreshes in [delayed_refresh()]. */
  boolean _refresh_pending = false;

  /** The estimated cursor position in code points. Used to avoid expensive IPC
      calls when the typed word can be estimated locally with [typed]. When the
      cursor position gets out of sync, the text before the cursor is queried
      again to the editor. */
  int _cursor;
  /** The cursor position within the current word relative to the end of the
      word in chars. Equal to [0] when the cursor is at the end of the word. */
  int _w_cursor;

  public CurrentlyTypedWord(Handler h, Callback cb)
  {
    _handler = h;
    _callback = cb;
  }

  public String get()
  {
    return _w.toString();
  }

  public boolean is_selection_not_empty()
  {
    return _has_selection;
  }

  /** Up to [CONTEXT_WORDS] words preceding the currently typed word, the
      most recent last. See [context_words]. */
  public String[] context()
  {
    return context_words(_before, _before_truncated, CONTEXT_WORDS);
  }

  /** Number of chars before the cursor to request from the editor. */
  public static final int TEXT_BEFORE_LENGTH = 64;
  /** Maximum length of [_before]. */
  static final int CONTEXT_CHARS = 48;
  static final int CONTEXT_WORDS = 2;

  /** The cursor position relative to the end of the word. */
  public int cursor_relative()
  {
    return _w_cursor;
  }

  public void started(Config conf, InputConnection ic)
  {
    _ic = ic;
    _enabled = true;
    EditorConfig e = conf.editor_config;
    _has_selection = e.initial_sel_start != e.initial_sel_end;
    _cursor = e.initial_sel_start;
    _w_cursor = 0;
    if (!_has_selection)
    {
      set_current_word(e.initial_text_before_cursor);
      _w_cursor = (e.initial_text_after_cursor == null) ? 0 :
        -append_chars(e.initial_text_after_cursor); 
    }
  }

  public void typed(String s)
  {
    if (!_enabled)
      return;
    _has_selection = false;
    // Typing a separator after a word finishes it. This is not done when the
    // word is refreshed from the editor, which would report the same words
    // again.
    String finished = null;
    String[] finished_context = null;
    if (_w_cursor == 0)
    {
      finished = finished_word(_w, s);
      if (finished != null)
        finished_context = context();
    }
    type_chars(s);
    if (finished != null)
      _callback.word_finished(finished_context, finished);
    callback();
  }

  /** The word finished by typing [s] after [word], or [null]. This is
      [word] followed by the word chars at the beginning of [s], when the rest
      of [s] only contains separators. For example, a space typed after a
      word or a suggestion followed by a space. Pasting several words doesn't
      finish a word. */
  static String finished_word(CharSequence word, String s)
  {
    int i = 0;
    while (i < s.length() && is_word_char(s.codePointAt(i)))
      i += Character.charCount(s.codePointAt(i));
    if (i == s.length() || (i == 0 && word.length() == 0))
      return null;
    for (int j = i; j < s.length();)
    {
      int c = s.codePointAt(j);
      if (is_word_char(c))
        return null;
      j += Character.charCount(c);
    }
    return word.toString() + s.substring(0, i);
  }

  public void selection_updated(int oldSelStart, int newSelStart, int newSelEnd)
  {
    // Avoid the expensive [refresh_current_word] call when [typed] was called
    // before.
    if (!_enabled)
      return;
    boolean new_has_sel = newSelStart != newSelEnd;
    if (new_has_sel || _has_selection) // Selection was on or is now on.
    {
      _cursor = newSelStart;
      _has_selection = new_has_sel;
      refresh_current_word();
    }
    else if (newSelStart != _cursor)
    {
      _cursor = newSelStart;
      _w_cursor += newSelStart - oldSelStart;
      if (_w_cursor < -_w.length() || _w_cursor > 0)
        refresh_current_word();
    }
  }

  public void event_sent(int code, int meta)
  {
    if (!_enabled)
      return;
    switch (code)
    {
      case KeyEvent.KEYCODE_DEL:
        if (meta == 0)
          remove_surrounding_text(1, 0);
        else
          delayed_refresh();
        break;
      default:
        delayed_refresh();
        break;
    }
  }

  public void remove_surrounding_text(int remove_before, int remove_after)
  {
    if (!_enabled)
      return;
    int len = _w.length();
    int c = len + _w_cursor;
    _w.delete(Math.max(c - remove_before, 0), Math.max(Math.min(c + remove_after, len), 0));
    _cursor -= remove_before;
    // The removed text extends past the boundaries of the tracked word. The
    // text that is now around the cursor might be part of a word that isn't
    // tracked, for example when removing the space after a word. Query the
    // editor once it has processed the change.
    boolean past_boundaries = remove_before > c || remove_after > -_w_cursor;
    _w_cursor = Math.min(_w_cursor + remove_after, 0);
    callback();
    if (past_boundaries)
      delayed_refresh();
  }

  void callback()
  {
    String w = _w.toString();
    _callback.currently_typed_word(w);
  }

  /** Estimate the currently typed word after [chars] has been typed. */
  void type_chars(CharSequence s, int start, int end)
  {
    int insert_start = 0;
    // Iterate over code points as that's the unit of [_cursor].
    for (int i = start; i < end;)
    {
      int c = Character.codePointAt(s, i);
      i += Character.charCount(c);
      _cursor++;
      // [i >= end] might happen when the cursor is in the middle of a
      // surrogate pair
      if (!is_word_char(c) && i <= end)
        insert_start = i;
    }
    if (insert_start > 0)
    {
      // The word before the cursor is finished, it becomes context.
      _before.append(_w, 0, Math.max(_w.length() + _w_cursor, 0));
      _before.append(s, start, insert_start);
      trim_before();
      _w.setLength(0);
    }
    _w.insert(Math.max(_w.length() + _w_cursor, 0), s, insert_start, end);
  }

  /** Keep [_before] at most [CONTEXT_CHARS] long. */
  void trim_before()
  {
    int excess = _before.length() - CONTEXT_CHARS;
    if (excess <= 0)
      return;
    // Don't split a surrogate pair.
    if (Character.isLowSurrogate(_before.charAt(excess)))
      excess++;
    _before.delete(0, excess);
    _before_truncated = true;
  }

  void type_chars(CharSequence s)
  {
    type_chars(s, 0, s.length());
  }

  /** Append chars to the current word without moving the cursor. Return the
      number of characters that were added in the current word. */
  int append_chars(CharSequence s, int start, int end)
  {
    int i = start;
    while (i < end)
    {
      int c = Character.codePointAt(s, i);
      if (!is_word_char(c))
        break;
      _w.appendCodePoint(c);
      i += Character.charCount(c);
    }
    return i - start;
  }

  int append_chars(CharSequence s)
  {
    return append_chars(s, 0, s.length());
  }

  /** Refresh the current word by immediately querying the editor. */
  void refresh_current_word()
  {
    Logs.debug("Refresh current word");
    _refresh_pending = false;
    _w_cursor = 0;
    if (_has_selection)
      set_current_word("");
    else if (VERSION.SDK_INT >= 31)
      set_current_word(_ic.getSurroundingText(TEXT_BEFORE_LENGTH, 20, 0));
    else
      set_current_word(_ic.getTextBeforeCursor(TEXT_BEFORE_LENGTH, 0));
  }

  /** Refresh the current word by immediately querying the editor. */
  void set_current_word(CharSequence text_before_cursor)
  {
    clear_word();
    if (text_before_cursor == null)
      return;
    _before_truncated = text_before_cursor.length() >= TEXT_BEFORE_LENGTH;
    int saved_cursor = _cursor;
    type_chars(text_before_cursor.toString());
    _cursor = saved_cursor;
    callback();
  }

  /** Like above but take the text after the cursor into account. */
  void set_current_word(SurroundingText st)
  {
    clear_word();
    if (st == null)
      return;
    int saved_cursor = _cursor;
    int st_sel = st.getSelectionStart();
    CharSequence st_text = st.getText();
    _before_truncated = st_sel >= TEXT_BEFORE_LENGTH;
    type_chars(st_text, 0, st_sel);
    _w_cursor = -append_chars(st_text, st_sel, st_text.length());
    _cursor = saved_cursor;
    callback();
  }

  void clear_word()
  {
    _w.setLength(0);
    _before.setLength(0);
    _before_truncated = false;
  }

  /** Wait some time to let the editor finishes reacting to changes and call
      [refresh_current_word]. */
  void delayed_refresh()
  {
    _refresh_pending = true;
    _handler.postDelayed(delayed_refresh_run, 50);
  }

  Runnable delayed_refresh_run = new Runnable()
  {
    public void run()
    {
      if (_refresh_pending)
        refresh_current_word();
    }
  };

  /** A word is the longest consecutive sequence for which [is_word_char]
      returns [true]. */
  public static boolean is_word_char(int c)
  {
    return Character.isLetterOrDigit(c) || (c == '\'');
  }

  /** Extract up to [max] words from the end of [text], the last word last.
      Stop at the beginning of the sentence, so the context is empty at the
      start of a sentence. If [truncated], the first word of [text] might be
      incomplete and is not returned. Separators other than sentence ends are
      skipped. */
  public static String[] context_words(CharSequence text, boolean truncated,
      int max)
  {
    String[] words = new String[max];
    int n = 0;
    int end = text.length();
    while (n < max)
    {
      // Skip separators, stopping at the end of a sentence.
      int i = end;
      while (i > 0)
      {
        int c = Character.codePointBefore(text, i);
        if (is_word_char(c))
          break;
        if (is_sentence_end(c))
          i = 0;
        else
          i -= Character.charCount(c);
      }
      if (i == 0)
        break;
      end = i;
      while (i > 0)
      {
        int c = Character.codePointBefore(text, i);
        if (!is_word_char(c))
          break;
        i -= Character.charCount(c);
      }
      if (i == 0 && truncated)
        break;
      words[n++] = text.subSequence(i, end).toString();
      end = i;
    }
    String[] res = new String[n];
    for (int j = 0; j < n; j++)
      res[j] = words[n - 1 - j];
    return res;
  }

  static boolean is_sentence_end(int c)
  {
    switch (c)
    {
      case '.': case '!': case '?': case '\n': case '\u2026': case '\u3002':
      case '\u00A1': case '\u00BF':
        return true;
      default:
        return false;
    }
  }

  public static interface Callback
  {
    public void currently_typed_word(String word);
    /** [word] was finished by typing a separator after it. [context] are the
        words before it, see [context()]. */
    public void word_finished(String[] context, String word);
  }
}
