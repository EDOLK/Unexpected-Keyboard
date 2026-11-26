package juloo.keyboard2.dict;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import juloo.keyboard2.Logs;
import juloo.keyboard2.R;
import juloo.keyboard2.Utils;

public class DictionariesActivity extends Activity
{
  static final int REQUEST_IMPORT_BIGRAMS = 1;

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

  public void import_bigrams(View v)
  {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    startActivityForResult(i, REQUEST_IMPORT_BIGRAMS);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data)
  {
    if (request != REQUEST_IMPORT_BIGRAMS || result != RESULT_OK
        || data == null || data.getData() == null)
      return;
    final Uri uri = data.getData();
    new Thread() {
      public void run()
      {
        final String msg = install_bigrams(uri);
        runOnUiThread(new Runnable() {
          public void run()
          {
            Toast.makeText(DictionariesActivity.this, msg, Toast.LENGTH_LONG)
              .show();
          }
        });
      }
    }.start();
  }

  /** Install a word pairs file, possibly gzip compressed. Return a message
      for the user. */
  String install_bigrams(Uri uri)
  {
    try
    {
      InputStream inp = new BufferedInputStream(
          getContentResolver().openInputStream(uri));
      inp.mark(2);
      boolean gzip = (inp.read() == 0x1f && inp.read() == 0x8b);
      inp.reset();
      if (gzip)
        inp = new GZIPInputStream(inp);
      byte[] bytes = Utils.read_all_bytes(inp);
      inp.close();
      Dictionaries dicts = Dictionaries.instance(this);
      String dict_name = dicts.install_bigrams(bytes);
      int msg = dicts.get_installed().contains(dict_name) ?
        R.string.bigrams_installed : R.string.bigrams_dict_not_installed;
      return getString(msg, dict_name);
    }
    catch (Exception e)
    {
      Logs.exn("import_bigrams", e);
      return getString(R.string.bigrams_import_failed, e.getMessage());
    }
  }
}
