package juloo.keyboard2;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.Iterator;
import juloo.keyboard2.suggestions.Suggestions;

public final class KeyEventHandler
  implements Config.IKeyEventHandler,
             ClipboardHistoryService.ClipboardPasteCallback,
             CurrentlyTypedWord.Callback
{
  Config _config;
  IReceiver _recv;
  Autocapitalisation _autocap;
  Suggestions _suggestions;
  CurrentlyTypedWord _typedword;
  /** State of the system modifiers. It is updated whether a modifier is down
      or up and a corresponding key event is sent. */
  Pointers.Modifiers _mods;
  /** Consistent with [_mods]. This is a mutable state rather than computed
      from [_mods] to ensure that the meta state is correct while up and down
      events are sent for the modifier keys. */
  int _meta_state = 0;
  /** Whether to force sending arrow keys to move the cursor when
      [setSelection] could be used instead. */
  boolean _move_cursor_force_fallback = false;
  /** Remember the action that was handled. This is used by autocorrect. */
  LastAction _last_action = null;
  LastAction _next_last_action = null;

  public KeyEventHandler(Config conf, IReceiver recv, Suggestions sg)
  {
    _config = conf;
    _recv = recv;
    Handler handler = recv.getHandler();
    _autocap = new Autocapitalisation(handler,
        this.new Autocapitalisation_callback());
    _mods = Pointers.Modifiers.EMPTY;
    _suggestions = sg;
    _typedword = new CurrentlyTypedWord(handler, this);
  }

  /** Editing just started. */
  public void started()
  {
    InputConnection ic = _recv.getCurrentInputConnection();
    _autocap.started(_config, ic);
    _typedword.started(_config, ic);
    _suggestions.started();
    _move_cursor_force_fallback =
      _config.editor_config.should_move_cursor_force_fallback;
    _last_action = null;
  }

  /** Selection has been updated. */
  public void selection_updated(int oldSelStart, int newSelStart, int newSelEnd)
  {
    _autocap.selection_updated(oldSelStart, newSelStart);
    _typedword.selection_updated(oldSelStart, newSelStart, newSelEnd);
    // The cursor moved away from the entered suggestion, backspace shouldn't
    // undo it anymore.
    if (_last_action == LastAction.SUGGESTION_ENTERED
        && (newSelStart != newSelEnd || newSelStart != last_suggestion_cursor))
      _last_action = LastAction.OTHER;
  }

  /** A key is being pressed. There will not necessarily be a corresponding
      [key_up] event. */
  @Override
  public void key_down(KeyValue key, boolean isSwipe)
  {
    if (key == null)
      return;
    // Stop auto capitalisation when pressing some keys
    switch (key.getKind())
    {
      case Modifier:
        switch (key.getModifier())
        {
          case CTRL:
          case ALT:
          case META:
            _autocap.stop();
            break;
        }
        break;
      case Compose_pending:
        _autocap.stop();
        break;
      case Slider:
        // Don't wait for the next key_up and move the cursor right away. This
        // is called after the trigger distance have been travelled.
        handle_slider(key.getSlider(), key.getSliderRepeat(), true);
        break;
      default: break;
    }
  }

  /** A key has been released. */
  @Override
  public void key_up(KeyValue key, Pointers.Modifiers mods)
  {
    if (key == null)
      return;
    _next_last_action = LastAction.OTHER;
    Pointers.Modifiers old_mods = _mods;
    update_meta_state(mods);
    switch (key.getKind())
    {
      case Char: handle_char(key.getChar()); break;
      case String: send_text(key.getString()); break;
      case Event: _recv.handle_event_key(key.getEvent()); break;
      case Keyevent: send_key_down_up(key.getKeyevent()); break;
      case Modifier: break;
      case Editing: handle_editing_key(key.getEditing()); break;
      case Compose_pending: _recv.set_compose_pending(true); break;
      case Slider: handle_slider(key.getSlider(), key.getSliderRepeat(), false); break;
      case Macro: evaluate_macro(key.getMacro()); break;
      case Stateful: handle_stateful(key.getStateful()); break;
    }
    update_meta_state(old_mods);
    _last_action = _next_last_action;
  }

  @Override
  public void mods_changed(Pointers.Modifiers mods)
  {
    update_meta_state(mods);
  }

  @Override
  public void suggestion_entered(String text)
  {
    suggestion_entered(text, _config.suggestions_add_space);
  }

  public void suggestion_entered(String text, boolean add_space_after)
  {
    if (add_space_after)
      text = text + " ";
    String old = _typedword.get();
    int cur_rel = _typedword.cursor_relative();
    replace_surrounding_text(old.length() + cur_rel, -cur_rel, text);
    last_replaced_word = old;
    last_replacement_word_len = text.length();
    last_suggestion_added_space = add_space_after;
    _next_last_action = LastAction.SUGGESTION_ENTERED;
    // Suggestions tapped in the candidates view are not entered from
    // [key_up], which would otherwise update [_last_action].
    _last_action = LastAction.SUGGESTION_ENTERED;
    last_suggestion_cursor = _typedword._cursor;
  }

  @Override
  public void paste_from_clipboard_pane(String content)
  {
    send_text(content);
  }

  @Override
  public void currently_typed_word(String word)
  {
    _suggestions.currently_typed_word(_typedword.context(), word);
  }

  public void dictionary_changed()
  {
    // Refresh the suggestions immediately after dictionary changed.
    _suggestions.currently_typed_word(_typedword.context(), _typedword.get());
  }

  /** Update [_mods] to be consistent with the [mods], sending key events if
      needed. */
  void update_meta_state(Pointers.Modifiers mods)
  {
    // Released modifiers
    Iterator<KeyValue> it = _mods.diff(mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), false);
    // Activated modifiers
    it = mods.diff(_mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), true);
    _mods = mods;
  }

  // private void handleDelKey(int before, int after)
  // {
  //  CharSequence selection = getCurrentInputConnection().getSelectedText(0);

  //  if (selection != null && selection.length() > 0)
  //  getCurrentInputConnection().commitText("", 1);
  //  else
  //  getCurrentInputConnection().deleteSurroundingText(before, after);
  // }

  void sendMetaKey(int eventCode, int meta_flags, boolean down)
  {
    if (down)
    {
      _meta_state = _meta_state | meta_flags;
      send_keyevent(KeyEvent.ACTION_DOWN, eventCode, _meta_state);
    }
    else
    {
      send_keyevent(KeyEvent.ACTION_UP, eventCode, _meta_state);
      _meta_state = _meta_state & ~meta_flags;
    }
  }

  void sendMetaKeyForModifier(KeyValue kv, boolean down)
  {
    switch (kv.getKind())
    {
      case Modifier:
        switch (kv.getModifier())
        {
          case CTRL:
            sendMetaKey(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON, down);
            break;
          case ALT:
            sendMetaKey(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_ON, down);
            break;
          case SHIFT:
            sendMetaKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON, down);
            break;
          case META:
            sendMetaKey(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_ON, down);
            break;
          default:
            break;
        }
        break;
    }
  }

  void send_key_down_up(int keyCode)
  {
    send_key_down_up(keyCode, _meta_state);
  }

  /** Ignores currently pressed system modifiers. */
  void send_key_down_up(int keyCode, int metaState)
  {
    send_keyevent(KeyEvent.ACTION_DOWN, keyCode, metaState);
    send_keyevent(KeyEvent.ACTION_UP, keyCode, metaState);
  }

  void send_keyevent(int eventAction, int eventCode, int metaState)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.sendKeyEvent(new KeyEvent(1, 1, eventAction, eventCode, 0,
          metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
          KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    if (eventAction == KeyEvent.ACTION_UP)
    {
      _autocap.event_sent(eventCode, metaState);
      _typedword.event_sent(eventCode, metaState);
    }
  }

  void send_text(String text)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    _autocap.typed(text);
    _typedword.typed(text);
    conn.commitText(text, 1);
  }

  /** Type a single character, handling the auto-close pairs option. */
  void send_char(char c)
  {
    if (!auto_close_pairs_enabled()
        || (AutoClosePairs.closing_of(c) == 0 && !AutoClosePairs.is_closing(c)))
    {
      send_text(String.valueOf(c));
      return;
    }
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    char close = AutoClosePairs.closing_of(c);
    if (close != 0 && _typedword.is_selection_not_empty())
    {
      // Surround the selection with the pair.
      CharSequence sel = conn.getSelectedText(0);
      if (sel != null && sel.length() > 0)
      {
        send_text(c + sel.toString() + close);
        return;
      }
    }
    char before = char_at_end(conn.getTextBeforeCursor(1, 0));
    char after = char_at_start(conn.getTextAfterCursor(1, 0));
    if (AutoClosePairs.should_overtype(c, after))
    {
      _autocap.typed(String.valueOf(c));
      move_cursor_right_no_mods(conn);
    }
    else if (AutoClosePairs.should_close(c, before, after))
      send_pair(conn, c, close);
    else
      send_text(String.valueOf(c));
  }

  /** Whether the auto-close pairs option applies to the current key press.
      It's disabled while system modifiers are pressed. */
  boolean auto_close_pairs_enabled()
  {
    final int system_mods =
      KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;
    return _config.auto_close_pairs && (_meta_state & system_mods) == 0;
  }

  /** Insert [open] and [close] and place the cursor between them. */
  void send_pair(InputConnection conn, char open, char close)
  {
    String open_s = String.valueOf(open);
    _autocap.typed(open_s);
    _typedword.typed(open_s);
    if (_move_cursor_force_fallback)
    {
      // The editor might not handle the cursor position passed to
      // [commitText], move it with a key event instead.
      conn.commitText(open_s + close, 1);
      send_key_down_up(KeyEvent.KEYCODE_DPAD_LEFT, 0);
      return;
    }
    conn.beginBatchEdit();
    conn.commitText(open_s, 1);
    // A cursor position of [0] places the cursor before the inserted text.
    conn.commitText(String.valueOf(close), 0);
    conn.endBatchEdit();
  }

  void move_cursor_right_no_mods(InputConnection conn)
  {
    if (!_move_cursor_force_fallback)
    {
      ExtractedText et = get_cursor_pos(conn);
      if (et != null && et.selectionStart == et.selectionEnd)
      {
        int pos = et.selectionStart + 1;
        if (conn.setSelection(pos, pos))
          return;
      }
    }
    send_key_down_up(KeyEvent.KEYCODE_DPAD_RIGHT, 0);
  }

  /** Delete an empty pair around the cursor. Returns [false] if there's no
      such pair. */
  boolean delete_auto_closed_pair()
  {
    if (!auto_close_pairs_enabled() || _typedword.is_selection_not_empty())
      return false;
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return false;
    char before = char_at_end(conn.getTextBeforeCursor(1, 0));
    char after = char_at_start(conn.getTextAfterCursor(1, 0));
    if (!AutoClosePairs.should_delete_pair(before, after))
      return false;
    conn.deleteSurroundingText(1, 1);
    _typedword.remove_surrounding_text(1, 1);
    return true;
  }

  static char char_at_start(CharSequence s)
  {
    return (s == null || s.length() == 0) ? 0 : s.charAt(0);
  }

  static char char_at_end(CharSequence s)
  {
    return (s == null || s.length() == 0) ? 0 : s.charAt(s.length() - 1);
  }

  void replace_surrounding_text(int remove_before, int remove_after,
      String new_text)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.beginBatchEdit();
    conn.deleteSurroundingText(remove_before, remove_after);
    conn.commitText(new_text, 1);
    _typedword.remove_surrounding_text(remove_before, remove_after);
    _typedword.typed(new_text);
    conn.endBatchEdit();
  }

  /** See {!InputConnection.performContextMenuAction}. */
  void send_context_menu_action(int id)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.performContextMenuAction(id);
  }

  @SuppressLint("InlinedApi")
  void handle_editing_key(KeyValue.Editing ev)
  {
    switch (ev)
    {
      case COPY: if(_typedword.is_selection_not_empty()) send_context_menu_action(android.R.id.copy); break;
      case PASTE: send_context_menu_action(android.R.id.paste); break;
      case CUT: if(_typedword.is_selection_not_empty()) send_context_menu_action(android.R.id.cut); break;
      case SELECT_ALL: send_context_menu_action(android.R.id.selectAll); break;
      case SHARE: send_context_menu_action(android.R.id.shareText); break;
      case PASTE_PLAIN: send_context_menu_action(android.R.id.pasteAsPlainText); break;
      case UNDO: send_context_menu_action(android.R.id.undo); break;
      case REDO: send_context_menu_action(android.R.id.redo); break;
      case REPLACE: send_context_menu_action(android.R.id.replaceText); break;
      case ASSIST: send_context_menu_action(android.R.id.textAssist); break;
      case AUTOFILL: send_context_menu_action(android.R.id.autofill); break;
      case DELETE_WORD: send_key_down_up(KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
      case FORWARD_DELETE_WORD: send_key_down_up(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
      case SELECTION_CANCEL: cancel_selection(); break;
      case SPACE_BAR: handle_space_bar(); break;
      case BACKSPACE: handle_backspace(); break;
    }
  }

  static ExtractedTextRequest _move_cursor_req = null;

  /** Query the cursor position. The extracted text is empty. Returns [null] if
      the editor doesn't support this operation. */
  ExtractedText get_cursor_pos(InputConnection conn)
  {
    if (_move_cursor_req == null)
    {
      _move_cursor_req = new ExtractedTextRequest();
      _move_cursor_req.hintMaxChars = 0;
    }
    return conn.getExtractedText(_move_cursor_req, 0);
  }

  /** [r] might be negative, in which case the direction is reversed. */
  void handle_slider(KeyValue.Slider s, int r, boolean key_down)
  {
    switch (s)
    {
      case Cursor_left: move_cursor(-r); break;
      case Cursor_right: move_cursor(r); break;
      case Cursor_up: move_cursor_vertical(-r); break;
      case Cursor_down: move_cursor_vertical(r); break;
      case Selection_cursor_left: move_cursor_sel(r, true, key_down); break;
      case Selection_cursor_right: move_cursor_sel(r, false, key_down); break;
    }
  }

  void handle_stateful(KeyValue.Stateful st)
  {
    switch (st)
    {
      case Complete_first:
      case Complete_second:
      case Complete_third:
      case Complete_emoji:
        suggestion_entered(st.toString());
        break;
    }
  }

  /** Move the cursor right or left, if possible without sending key events.
      Unlike arrow keys, the selection is not removed even if shift is not on.
      Falls back to sending arrow keys events if the editor do not support
      moving the cursor or a modifier other than shift is pressed. */
  void move_cursor(int d)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Continue expanding the selection even if shift is not pressed
      if (sel_end != sel_start)
      {
        sel_end += d;
        if (sel_end == sel_start) // Avoid making the selection empty
          sel_end += d;
      }
      else
      {
        sel_end += d;
        // Leave 'sel_start' where it is if shift is pressed
        if ((_meta_state & KeyEvent.META_SHIFT_ON) == 0)
          sel_start = sel_end;
      }
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Move one of the two side of a selection. If [sel_left] is true, the left
      position is moved, otherwise the right position is moved. */
  void move_cursor_sel(int d, boolean sel_left, boolean key_down)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Reorder the selection when the slider has just been pressed. The
      // selection might have been reversed if one end crossed the other end
      // with a previous slider.
      if (key_down && sel_start > sel_end)
      {
        sel_start = et.selectionEnd;
        sel_end = et.selectionStart;
      }
      do
      {
        if (sel_left)
          sel_start += d;
        else
          sel_end += d;
        // Move the cursor twice if moving it once would make the selection
        // empty and stop selection mode.
      } while (sel_start == sel_end);
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Returns whether the selection can be set using [conn.setSelection()].
      This can happen on Termux or when system modifiers are activated for
      example. */
  boolean can_set_selection(InputConnection conn)
  {
    final int system_mods =
      KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;
    return !_move_cursor_force_fallback && (_meta_state & system_mods) == 0;
  }

  void move_cursor_fallback(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_LEFT, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_RIGHT, d);
  }

  /** Move the cursor up and down. This sends UP and DOWN key events that might
      make the focus exit the text box. */
  void move_cursor_vertical(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_UP, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_DOWN, d);
  }

  void evaluate_macro(KeyValue[] keys)
  {
    if (keys.length == 0)
      return;
    // Ignore modifiers that are activated at the time the macro is evaluated
    mods_changed(Pointers.Modifiers.EMPTY);
    evaluate_macro_loop(keys, 0, Pointers.Modifiers.EMPTY, _autocap.pause());
  }

  /** Evaluate the macro asynchronously to make sure event are processed in the
      right order. */
  void evaluate_macro_loop(final KeyValue[] keys, int i, Pointers.Modifiers mods, final boolean autocap_paused)
  {
    boolean should_delay = false;
    KeyValue kv = KeyModifier.modify_no_modmap(keys[i], mods);
    if (kv != null)
    {
      if (kv.hasFlagsAny(KeyValue.FLAG_LATCH))
      {
        // Non-special latchable keys clear latched modifiers
        if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL))
          mods = Pointers.Modifiers.EMPTY;
        mods = mods.with_extra_mod(kv);
      }
      else
      {
        key_down(kv, false);
        key_up(kv, mods);
        mods = Pointers.Modifiers.EMPTY;
      }
      should_delay = wait_after_macro_key(kv);
    }
    i++;
    if (i >= keys.length) // Stop looping
    {
      _autocap.unpause(autocap_paused);
    }
    else if (should_delay)
    {
      // Add a delay before sending the next key to avoid race conditions
      // causing keys to be handled in the wrong order. Notably, KeyEvent keys
      // handling is scheduled differently than the other edit functions.
      final int i_ = i;
      final Pointers.Modifiers mods_ = mods;
      _recv.getHandler().postDelayed(new Runnable() {
        public void run()
        {
          evaluate_macro_loop(keys, i_, mods_, autocap_paused);
        }
      }, 1000/30);
    }
    else
      evaluate_macro_loop(keys, i, mods, autocap_paused);
  }

  boolean wait_after_macro_key(KeyValue kv)
  {
    switch (kv.getKind())
    {
      case Keyevent:
      case Editing:
      case Event:
        return true;
      case Slider:
        return _move_cursor_force_fallback;
      default:
        return false;
    }
  }

  /** Repeat calls to [send_key_down_up]. */
  void send_key_down_up_repeat(int event_code, int repeat)
  {
    while (repeat-- > 0)
      send_key_down_up(event_code);
  }

  void cancel_selection()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et == null) return;
    final int curs = et.selectionStart;
    // Notify the receiver as Android's [onUpdateSelection] is not triggered.
    if (conn.setSelection(curs, curs))
      _recv.selection_state_changed(false);
  }

  /** The text that was replaced by a suggestion when the last action was to
      enter a suggestion (with the space bar or the candidates view) or [null]
      otherwise. Backspace restores it. Includes the space typed by the user
      when the suggestion was entered with the space bar. */
  String last_replaced_word = null;
  /** Length of the text before the cursor that should be replaced by
      backspace. */
  int last_replacement_word_len = 0;
  /** Cursor position right after the last entered suggestion. */
  int last_suggestion_cursor = -1;
  /** Whether a space was added after the last entered suggestion. */
  boolean last_suggestion_added_space = false;

  /** Type a character, automatically adding a space after punctuation if
      enabled in the settings. */
  void handle_char(char c)
  {
    if (_config.auto_space_after_punctuation
        && _config.editor_config.auto_space_allowed
        && !_typedword.is_selection_not_empty())
    {
      // A space was just inserted automatically and the user types more
      // punctuation or a closing bracket: "word. " + '?' => "word.? "
      boolean space_was_added =
        _last_action == LastAction.AUTO_SPACE
        || (_last_action == LastAction.SUGGESTION_ENTERED
            && last_suggestion_added_space);
      if (space_was_added
          && (is_auto_space_punctuation(c) || is_closing_punctuation(c)))
      {
        replace_auto_space(c + " ");
        _next_last_action = LastAction.AUTO_SPACE;
        return;
      }
      if (is_auto_space_punctuation(c) && cursor_is_after_letter()
          && !text_before_cursor_looks_like_url())
      {
        send_text(c + " ");
        _next_last_action = LastAction.AUTO_SPACE;
        return;
      }
    }
    send_char(c);
  }

  /** Characters after which a space is automatically inserted. */
  static boolean is_auto_space_punctuation(char c)
  {
    switch (c)
    {
      case '.': case ',': case '!': case '?': case ';': case ':':
      case '\u2026': // Ellipsis
        return true;
      default:
        return false;
    }
  }

  /** Characters that go before the automatically inserted space. Straight
      quotes are excluded as they are ambiguous. */
  static boolean is_closing_punctuation(char c)
  {
    switch (c)
    {
      case ')': case ']': case '}':
      case '\u201D': case '\u2019': case '\u00BB':
        return true;
      default:
        return false;
    }
  }

  /** Whether the cursor is at the end of a word that ends with a letter. This
      avoids inserting a space in numbers like "3.5" or "12:30" and in the
      middle of words. */
  boolean cursor_is_after_letter()
  {
    String w = _typedword.get();
    if (w.length() == 0 || _typedword.cursor_relative() != 0)
      return false;
    return Character.isLetter(w.codePointBefore(w.length()));
  }

  /** Whether the text typed since the last whitespace looks like a URL or an
      email address, for example "https://example" or "www.example". This
      catches URLs typed in fields that are not marked as URL fields. */
  boolean text_before_cursor_looks_like_url()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return false;
    CharSequence before = conn.getTextBeforeCursor(64, 0);
    if (before == null)
      return false;
    int start = before.length();
    while (start > 0 && !Character.isWhitespace(before.charAt(start - 1)))
      start--;
    String token = before.subSequence(start, before.length()).toString()
      .toLowerCase();
    return token.contains("://") || token.startsWith("www.")
      || token.indexOf('@') >= 0;
  }

  /** Remove the automatically inserted space before the cursor and type
      [text]. */
  void replace_auto_space(String text)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.beginBatchEdit();
    conn.deleteSurroundingText(1, 0);
    _typedword.remove_surrounding_text(1, 0);
    _autocap.removed_before_cursor(1);
    send_text(text);
    conn.endBatchEdit();
  }

  /** Implement autocorrect when enabled in the settings. */
  void handle_space_bar()
  {
    // A space was just inserted automatically after punctuation, don't insert
    // a second one.
    if (_last_action == LastAction.AUTO_SPACE)
      return;
    // Next word predictions are shown when less than 2 letters are typed,
    // they must not be entered on space.
    if (_config.space_bar_auto_complete && _suggestions.count > 0
        && _typedword.get().length() >= 2
        && !_typedword.is_selection_not_empty()
        && _typedword.cursor_relative() == 0
        && _last_action == LastAction.OTHER)
    {
      // Don't replace the typed word if unecessary to avoid flickering
      String w = _suggestions.suggestions[0];
      if (!w.equals(_typedword.get()))
      {
        suggestion_entered(w, true);
        // The user typed a space, restore it when undoing.
        last_replaced_word = last_replaced_word + " ";
        return;
      }
    }
    send_text(" ");
  }

  /** Undo the last autocorrect. */
  void handle_backspace()
  {
    if (_last_action == LastAction.SUGGESTION_ENTERED
        && last_replaced_word != null)
    {
      replace_surrounding_text(last_replacement_word_len, 0, last_replaced_word);
      last_replaced_word = null;
      _next_last_action = LastAction.SUGGESTION_UNDO;
    }
    else if (!delete_auto_closed_pair())
    {
      send_key_down_up(KeyEvent.KEYCODE_DEL);
    }
  }

  public static interface IReceiver extends Suggestions.Callback
  {
    public void handle_event_key(KeyValue.Event ev);
    public void set_shift_state(boolean state, boolean lock);
    public void set_compose_pending(boolean pending);
    public void selection_state_changed(boolean selection_is_ongoing);
    public InputConnection getCurrentInputConnection();
    public Handler getHandler();
  }

  class Autocapitalisation_callback implements Autocapitalisation.Callback
  {
    @Override
    public void update_shift_state(boolean should_enable, boolean should_disable)
    {
      if (should_enable)
        _recv.set_shift_state(true, false);
      else if (should_disable)
        _recv.set_shift_state(false, false);
    }
  }

  public static enum LastAction
  {
    SUGGESTION_ENTERED,
    SUGGESTION_UNDO,
    /** A space was automatically inserted after punctuation. */
    AUTO_SPACE,
    OTHER
  }
}
