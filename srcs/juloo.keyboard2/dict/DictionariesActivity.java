package juloo.keyboard2.dict;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import juloo.keyboard2.R;

public class DictionariesActivity extends Activity
{
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.dictionaries_activity);
  }

  public void launch_user_dictionary_activity(View v)
  {
    startActivity(new Intent(this, UserDictionaryActivity.class));
  }
}
