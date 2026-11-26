package juloo.keyboard2.dict;

import android.content.Context;
import android.os.Build.VERSION;
import android.os.UserManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Word pairs learned from what the user types: how many times each word was
    typed after another one.

    Stored in the credential protected storage because this is personal. It is
    not available before the device is unlocked for the first time; loading is
    retried on every access until it succeeds. */
public final class UserBigrams
{
  public static UserBigrams instance(Context ctx)
  {
    if (_instance == null)
    {
      Context app = ctx.getApplicationContext();
      _instance = new UserBigrams(new FileStorage(app != null ? app : ctx));
    }
    return _instance;
  }

  /** Record that [next] was typed after [prev]. */
  public void learn(String prev, String next)
  {
    if (!ensure_loaded())
      return;
    add(key(prev), next, 1);
    _dirty = true;
    if (_size > MAX_PAIRS)
      evict();
  }

  /** Words typed after [prev] with how many times they were, most frequent
      first. [prev] is matched ignoring case. */
  public List<Pair> successors(String prev)
  {
    List<Pair> res = new ArrayList<Pair>();
    if (!ensure_loaded())
      return res;
    Map<String, int[]> m = _pairs.get(key(prev));
    if (m == null)
      return res;
    for (Map.Entry<String, int[]> e : m.entrySet())
      res.add(new Pair(e.getKey(), e.getValue()[0]));
    Collections.sort(res, Pair.MOST_FREQUENT_FIRST);
    return res;
  }

  /** Words following [prev] from both the [base] word pairs (might be
      [null]) and the learned pairs, most likely first.
      Weights are normalized: pairs from [base] get up to [BASE_MAX_WEIGHT]
      and learned pairs get from [LEARNED_MIN_WEIGHT] to [Bigrams.MAX_WEIGHT]
      according to how many times they were typed, relative to the most typed
      pair. A pair must be typed [LEARNED_SATURATION] times to get the maximum
      weight. A pair in both gets the sum. */
  public Bigrams.Successors merge(Bigrams base, String prev)
  {
    return merge(base, successors(prev), prev);
  }

  /** Like [merge] with the learned pairs given. [learned] must be sorted,
      most frequent first. */
  public static Bigrams.Successors merge(Bigrams base, List<Pair> learned,
      String prev)
  {
    Bigrams.Successors b =
      (base == null) ? Bigrams.NONE : base.successors(prev);
    int base_max = 1;
    for (int w : b.weights)
      base_max = Math.max(base_max, w);
    // Lowercased word to (word, weight). Keep the insertion order for ties.
    Map<String, Pair> weights = new java.util.LinkedHashMap<String, Pair>();
    // Relative to the most typed pair, to keep the order. Pairs typed only a
    // few times get a lower weight.
    int max_count = LEARNED_SATURATION;
    for (Pair p : learned)
      max_count = Math.max(max_count, p.count);
    for (Pair p : learned)
      weights.put(key(p.word), new Pair(p.word, LEARNED_MIN_WEIGHT
            + (Bigrams.MAX_WEIGHT - LEARNED_MIN_WEIGHT) * p.count / max_count));
    for (int i = 0; i < b.words.length; i++)
    {
      int w = Math.max(1, BASE_MAX_WEIGHT * b.weights[i] / base_max);
      Pair p = weights.get(key(b.words[i]));
      if (p == null)
        weights.put(key(b.words[i]), new Pair(b.words[i], w));
      else
        weights.put(key(b.words[i]),
            new Pair(p.word, Math.min(Bigrams.MAX_WEIGHT, p.count + w)));
    }
    List<Pair> all = new ArrayList<Pair>(weights.values());
    Collections.sort(all, Pair.MOST_FREQUENT_FIRST);
    Bigrams.Successors s = new Bigrams.Successors(all.size());
    for (int i = 0; i < all.size(); i++)
    {
      s.words[i] = all.get(i).word;
      s.weights[i] = all.get(i).count;
    }
    return s;
  }

  /** Forget every learned pair. Does nothing if the storage is locked. */
  public void clear()
  {
    // Load first, otherwise there would be nothing to save.
    if (!ensure_loaded())
      return;
    _pairs.clear();
    _size = 0;
    _dirty = true;
    save();
  }

  /** Number of learned pairs. */
  public int size()
  {
    ensure_loaded();
    return _size;
  }

  /** Save to storage if there are unsaved changes. The data is copied and
      written in the background. */
  public void save()
  {
    if (!_dirty || !_loaded)
      return;
    _dirty = false;
    final String data = serialize();
    new Thread() {
      public void run()
      {
        synchronized (_storage)
        {
          try { _storage.write(data); }
          catch (IOException e) {}
        }
      }
    }.start();
  }

  /** A word and how many times it was typed, or its weight in [merge]. */
  public static final class Pair
  {
    public final String word;
    public final int count;
    public Pair(String w, int c) { word = w; count = c; }

    static final Comparator<Pair> MOST_FREQUENT_FIRST = new Comparator<Pair>() {
      public int compare(Pair a, Pair b)
      {
        if (a.count != b.count)
          return b.count - a.count;
        return a.word.compareTo(b.word);
      }
    };
  }

  /** Maximum number of pairs kept. The least frequent are forgotten. */
  static final int MAX_PAIRS = 30000;
  /** A pair must be typed at least that many times to get the maximum
      weight. */
  static final int LEARNED_SATURATION = 5;
  static final int LEARNED_MIN_WEIGHT = 128;
  static final int BASE_MAX_WEIGHT = 85;

  /** Where the pairs are stored. Abstracted for testing. */
  public static interface Storage
  {
    /** Return [null] if nothing is stored. Throws if not available yet. */
    public String read() throws IOException;
    public void write(String data) throws IOException;
  }

  public UserBigrams(Storage storage)
  {
    _storage = storage;
  }

  /** Private */

  static UserBigrams _instance = null;

  final Storage _storage;
  /** Lowercased previous word to next word (as typed) to count. */
  final Map<String, Map<String, int[]>> _pairs =
    new HashMap<String, Map<String, int[]>>();
  int _size = 0;
  boolean _loaded = false;
  boolean _dirty = false;

  boolean ensure_loaded()
  {
    if (_loaded)
      return true;
    String data;
    try
    {
      synchronized (_storage) { data = _storage.read(); }
    }
    catch (Exception e)
    {
      // Credential protected storage is locked during direct boot.
      return false;
    }
    _loaded = true;
    if (data != null)
      deserialize(data);
    return true;
  }

  void add(String prev, String next, int count)
  {
    Map<String, int[]> m = _pairs.get(prev);
    if (m == null)
    {
      m = new HashMap<String, int[]>();
      _pairs.put(prev, m);
    }
    int[] c = m.get(next);
    if (c == null)
    {
      m.put(next, new int[]{ count });
      _size++;
    }
    else
      c[0] += count;
  }

  /** Forget the least frequent pairs, a tenth of them at once to not do it on
      every word. */
  void evict()
  {
    List<Object[]> all = new ArrayList<Object[]>(); // prev, next, count
    for (Map.Entry<String, Map<String, int[]>> e : _pairs.entrySet())
      for (Map.Entry<String, int[]> n : e.getValue().entrySet())
        all.add(new Object[]{ e.getKey(), n.getKey(), n.getValue()[0] });
    Collections.sort(all, new Comparator<Object[]>() {
      public int compare(Object[] a, Object[] b)
      { return (Integer)a[2] - (Integer)b[2]; }
    });
    int remove = _size - MAX_PAIRS * 9 / 10;
    for (int i = 0; i < remove; i++)
    {
      Map<String, int[]> m = _pairs.get((String)all.get(i)[0]);
      m.remove((String)all.get(i)[1]);
      if (m.isEmpty())
        _pairs.remove((String)all.get(i)[0]);
      _size--;
    }
  }

  /** One pair per line: "prev\tnext\tcount". Words can't contain tabs or
      newlines. */
  String serialize()
  {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, Map<String, int[]>> e : _pairs.entrySet())
      for (Map.Entry<String, int[]> n : e.getValue().entrySet())
        b.append(e.getKey()).append('\t').append(n.getKey()).append('\t')
          .append(n.getValue()[0]).append('\n');
    return b.toString();
  }

  void deserialize(String data)
  {
    for (String line : data.split("\n"))
    {
      String[] f = line.split("\t");
      if (f.length != 3 || f[0].isEmpty() || f[1].isEmpty())
        continue;
      try
      {
        int c = Integer.parseInt(f[2]);
        if (c > 0)
          add(f[0], f[1], c);
      }
      catch (NumberFormatException e) {}
    }
  }

  static String key(String word)
  {
    return word.toLowerCase(Locale.ROOT);
  }

  static final class FileStorage implements Storage
  {
    final Context _context;
    FileStorage(Context ctx) { _context = ctx; }

    File file() throws IOException
    {
      // The credential protected storage might look empty while locked,
      // reading it then would lose the learned pairs on the next save.
      if (VERSION.SDK_INT >= 24)
      {
        UserManager um =
          (UserManager)_context.getSystemService(Context.USER_SERVICE);
        if (um != null && !um.isUserUnlocked())
          throw new IOException("Storage locked");
      }
      return new File(_context.getFilesDir(), "user_bigrams.txt");
    }

    public String read() throws IOException
    {
      File f = file();
      if (!f.exists())
        return null;
      StringBuilder b = new StringBuilder();
      BufferedReader r = new BufferedReader(
          new InputStreamReader(new FileInputStream(f), "UTF-8"));
      char[] buf = new char[8192];
      int n;
      while ((n = r.read(buf)) > 0)
        b.append(buf, 0, n);
      r.close();
      return b.toString();
    }

    public void write(String data) throws IOException
    {
      File f = file();
      File tmp = new File(f.getPath() + ".tmp");
      Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
      w.write(data);
      w.close();
      if (!tmp.renameTo(f))
        throw new IOException("Rename failed");
    }
  }
}
