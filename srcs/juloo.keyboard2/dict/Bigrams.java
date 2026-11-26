package juloo.keyboard2.dict;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Locale;

/** Word pairs: which words are likely to follow a given word. Built by
    [scripts/gen_bigrams.py], which describes the format.

    The file is not decoded on load, lookups are done by binary search
    directly in the bytes to avoid allocating a [String] for every word. */
public final class Bigrams
{
  /** Name of the dictionary the file was built for, eg. "en_US". */
  public final String dict_name;

  /** Words following [prev], most likely first. */
  public static final class Successors
  {
    public final String[] words;
    /** From [1] to [MAX_WEIGHT], higher is more likely. */
    public final int[] weights;
    Successors(int n) { words = new String[n]; weights = new int[n]; }
  }

  public static final int MAX_WEIGHT = 255;
  public static final Successors NONE = new Successors(0);

  /** Load a file. Throws [Error] if the file is invalid. The data is not
      copied and must not be modified. */
  public static Bigrams of_bytes(byte[] data) throws Error
  {
    try
    {
      return new Bigrams(ByteBuffer.wrap(data));
    }
    catch (IndexOutOfBoundsException e)
    {
      throw new Error("Truncated file");
    }
  }

  /** Words that follow [prev]. If [prev] is not found, the lowercase version
      is tried. Never returns [null]. */
  public Successors successors(String prev)
  {
    int i = find_word(prev);
    if (i < 0)
      return NONE;
    int start = _buf.getInt(_succ_off + 4 * i);
    int end = _buf.getInt(_succ_off + 4 * (i + 1));
    Successors s = new Successors(end - start);
    for (int j = start; j < end; j++)
    {
      int e = _buf.getInt(_succ + 4 * j);
      s.words[j - start] = word(e >>> 8);
      s.weights[j - start] = e & 0xFF;
    }
    return s;
  }

  /** Number of distinct words. */
  public int size() { return _n_words; }

  public static final class Error extends Exception
  {
    public Error(String msg) { super(msg); }
  }

  /** Private */

  static final Charset UTF8 = Charset.forName("UTF-8");
  /** Version of the format. */
  public static final int VERSION = 1;

  final ByteBuffer _buf;
  final int _n_words;
  /** Offsets of the sections in [_buf]. */
  final int _word_off;
  final int _succ_off;
  final int _strings;
  final int _succ;

  Bigrams(ByteBuffer buf) throws Error
  {
    _buf = buf;
    if (buf.get(0) != 'U' || buf.get(1) != 'K' || buf.get(2) != 'B'
        || buf.get(3) != 'G')
      throw new Error("Not a bigrams file");
    if (buf.get(4) != VERSION)
      throw new Error("Unsupported version " + buf.get(4));
    int name_len = buf.getShort(8) & 0xFFFF;
    dict_name = new String(buf.array(), 10, name_len, UTF8);
    int p = 10 + name_len;
    _n_words = buf.getInt(p);
    _word_off = p + 4;
    _succ_off = _word_off + 4 * (_n_words + 1);
    int str_len_pos = _succ_off + 4 * (_n_words + 1);
    _strings = str_len_pos + 4;
    int str_len = buf.getInt(str_len_pos);
    int n_succ_pos = _strings + str_len;
    _succ = n_succ_pos + 4;
    int n_succ = buf.getInt(n_succ_pos);
    if (_n_words < 0 || n_succ < 0 || _succ + 4L * n_succ > buf.limit()
        || buf.getInt(_word_off + 4 * _n_words) != str_len
        || buf.getInt(_succ_off + 4 * _n_words) != n_succ)
      throw new Error("Invalid file");
    // Check every offsets and indices once so that lookups can't fail.
    int prev_w = 0, prev_s = 0;
    for (int i = 0; i <= _n_words; i++)
    {
      int w = buf.getInt(_word_off + 4 * i);
      int s = buf.getInt(_succ_off + 4 * i);
      if (w < prev_w || s < prev_s)
        throw new Error("Invalid offsets");
      prev_w = w; prev_s = s;
    }
    for (int j = 0; j < n_succ; j++)
      if ((buf.getInt(_succ + 4 * j) >>> 8) >= _n_words)
        throw new Error("Invalid word index");
  }

  int find_word(String w)
  {
    int i = binary_search(w.getBytes(UTF8));
    if (i < 0)
    {
      String lower = w.toLowerCase(Locale.ROOT);
      if (!lower.equals(w))
        i = binary_search(lower.getBytes(UTF8));
    }
    return i;
  }

  /** Index of the word [w] or [-1]. */
  int binary_search(byte[] w)
  {
    int lo = 0, hi = _n_words - 1;
    while (lo <= hi)
    {
      int mid = (lo + hi) >>> 1;
      int c = compare(mid, w);
      if (c == 0)
        return mid;
      if (c < 0)
        lo = mid + 1;
      else
        hi = mid - 1;
    }
    return -1;
  }

  /** Compare word [i] with [w], as unsigned bytes. */
  int compare(int i, byte[] w)
  {
    int start = _strings + _buf.getInt(_word_off + 4 * i);
    int len = _strings + _buf.getInt(_word_off + 4 * (i + 1)) - start;
    int n = Math.min(len, w.length);
    for (int j = 0; j < n; j++)
    {
      int a = _buf.get(start + j) & 0xFF, b = w[j] & 0xFF;
      if (a != b)
        return a - b;
    }
    return len - w.length;
  }

  String word(int i)
  {
    int start = _buf.getInt(_word_off + 4 * i);
    int end = _buf.getInt(_word_off + 4 * (i + 1));
    return new String(_buf.array(), _strings + start, end - start, UTF8);
  }
}
