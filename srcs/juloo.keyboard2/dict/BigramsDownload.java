package juloo.keyboard2.dict;

import android.content.Context;
import android.content.res.Resources;
import android.util.Base64;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import juloo.keyboard2.Logs;
import juloo.keyboard2.R;
import juloo.keyboard2.Utils;

/** Download word pairs files. They are built by [scripts/build_bigrams.py]
    and listed in [res/values/bigrams.xml]. */
public final class BigramsDownload
{
  /** Served from the [bigrams] branch of the repository. */
  static final String REPO_URL =
    "https://raw.githubusercontent.com/EDOLK/Unexpected-Keyboard/refs/heads/bigrams";

  /** Whether word pairs can be downloaded for [dict_name]. */
  public static boolean available(Resources res, String dict_name)
  {
    return find(res, dict_name) >= 0;
  }

  /** Download and install the word pairs for [dict_name]. Must not be called
      from the UI thread. */
  public static void install(Context ctx, String dict_name) throws Exception
  {
    Resources res = ctx.getResources();
    int i = find(res, dict_name);
    if (i < 0)
      throw new Exception("No word pairs for " + dict_name);
    URLConnection con = new URL(REPO_URL + "/v" + Bigrams.VERSION + "/"
        + dict_name + ".bigrams").openConnection();
    // Remote files are compressed with gzip at rest. Do not use server side
    // compression and force decompression.
    con.setRequestProperty("Accept-Encoding", "identity");
    byte[] data =
      Utils.read_all_bytes(new GZIPInputStream(con.getInputStream()));
    if (data.length != res.getIntArray(R.array.bigrams_size)[i])
      throw new Exception("Size mismatch");
    byte[] expected = Base64.decode(
        res.getStringArray(R.array.bigrams_sha256)[i], Base64.DEFAULT);
    byte[] actual = MessageDigest.getInstance("SHA-256").digest(data);
    if (!Arrays.equals(expected, actual))
      throw new Exception("Checksum mismatch");
    Dictionaries dicts = Dictionaries.instance(ctx);
    String name = dicts.install_bigrams(data);
    if (!name.equals(dict_name))
    {
      dicts.uninstall_bigrams(name);
      throw new Exception("Word pairs are for " + name);
    }
  }

  /** Download the word pairs of installed dictionaries that don't have them
      yet. Must not be called from the UI thread. Return the number of files
      installed. Throws the first error, if no file could be installed. */
  public static int install_missing(Context ctx) throws Exception
  {
    Dictionaries dicts = Dictionaries.instance(ctx);
    int installed = 0;
    Exception first_error = null;
    for (String d : dicts.get_installed())
    {
      if (dicts.has_bigrams(d) || !available(ctx.getResources(), d))
        continue;
      try
      {
        install(ctx, d);
        installed++;
      }
      catch (Exception e)
      {
        Logs.exn(d, e);
        if (first_error == null)
          first_error = e;
      }
    }
    if (installed == 0 && first_error != null)
      throw first_error;
    return installed;
  }

  static int find(Resources res, String dict_name)
  {
    String[] names = res.getStringArray(R.array.bigrams_dict_name);
    for (int i = 0; i < names.length; i++)
      if (names[i].equals(dict_name))
        return i;
    return -1;
  }
}
