package juloo.keyboard2.dict;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import juloo.keyboard2.R;

/** List the words of the [UserDictionary] and allow adding and removing
    words. */
public class UserDictionaryActivity extends Activity
{
  UserDictionary _user_dict;
  EditText _input;
  ViewGroup _list;
  View _empty;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.user_dictionary_activity);
    _user_dict = UserDictionary.instance(this);
    _input = (EditText)findViewById(R.id.user_dictionary_input);
    _list = (ViewGroup)findViewById(R.id.user_dictionary_list);
    _empty = findViewById(R.id.user_dictionary_empty);
    findViewById(R.id.user_dictionary_add).setOnClickListener(
        new View.OnClickListener()
        {
          @Override
          public void onClick(View v) { add_input_word(); }
        });
    _input.setOnEditorActionListener(new TextView.OnEditorActionListener()
        {
          @Override
          public boolean onEditorAction(TextView v, int action, KeyEvent ev)
          {
            if (action != EditorInfo.IME_ACTION_DONE)
              return false;
            add_input_word();
            return true;
          }
        });
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    // Words might have been added from the keyboard.
    refresh();
  }

  void add_input_word()
  {
    String word = _input.getText().toString().trim();
    if (word.isEmpty())
      return;
    for (int i = 0; i < word.length(); i++)
      if (Character.isWhitespace(word.charAt(i)))
      {
        Toast.makeText(this, R.string.user_dictionary_no_spaces,
            Toast.LENGTH_SHORT).show();
        return;
      }
    if (_user_dict.add(word))
      _input.setText("");
    refresh();
  }

  void refresh()
  {
    _list.removeAllViews();
    LayoutInflater inflater = LayoutInflater.from(this);
    int count = 0;
    for (final String word : _user_dict.get_all())
    {
      View v = inflater.inflate(R.layout.dictionary_download_item, _list,
          false);
      ((TextView)v.findViewById(R.id.dictionary_download_locale))
        .setText(word);
      v.findViewById(R.id.dictionary_download_size).setVisibility(View.GONE);
      ImageView btn = (ImageView)v.findViewById(R.id.dictionary_download_button);
      btn.setImageResource(R.drawable.ic_delete);
      btn.setContentDescription(
          getResources().getString(R.string.user_dictionary_remove, word));
      btn.setOnClickListener(new View.OnClickListener()
          {
            @Override
            public void onClick(View _v)
            {
              _user_dict.remove(word);
              refresh();
            }
          });
      _list.addView(v);
      count++;
    }
    _empty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
  }
}
