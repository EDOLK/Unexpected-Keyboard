package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.KeyboardData;

/** How likely a character is to be typed instead of another, based on where
    they are on the layout. Characters are compared after lowercasing and
    applying [CdictEngine.apply_substitutions], like dictionary words.

    Two kinds of mistakes are cheap:
    - Swiping in the wrong direction or not swiping, which produces another
      character of the same key.
    - Hitting a neighbor key. */
public final class KeyProximity
{
  /** Cost of substituting [intended] by [typed], between [0] (same char) and
      [1] (unrelated chars). Symmetric. */
  public float substitution_cost(int typed, int intended)
  {
    typed = normalize(typed);
    intended = normalize(intended);
    if (typed == intended)
      return 0.f;
    Pos[] ps_a = _positions.get(typed);
    Pos[] ps_b = _positions.get(intended);
    if (ps_a == null || ps_b == null)
      return 1.f;
    float best = 1.f;
    // A char might appear on several keys.
    for (Pos a : ps_a)
      for (Pos b : ps_b)
        best = Math.min(best, pos_cost(a, b));
    return best;
  }

  /** Cost of a swipe in the wrong direction on the same key. */
  public static final float SAME_KEY_CENTER = 0.5f;
  public static final float SAME_KEY_ADJACENT_DIR = 0.6f;
  public static final float SAME_KEY_OTHER_DIR = 0.8f;
  /** Cost of hitting a neighbor key instead of the intended one. */
  public static final float NEIGHBOR_KEY = 0.6f;
  /** Added when one of the char is not on the center of its key. */
  public static final float NEIGHBOR_KEY_CORNER = 0.25f;
  /** Maximum distance between the center of keys, in key widths, for them to
      be considered neighbors. Diagonal neighbors on staggered rows are about
      1.12 apart. */
  static final float NEIGHBOR_DISTANCE = 1.3f;

  public static final KeyProximity EMPTY = new KeyProximity();

  public static KeyProximity of_layout(KeyboardData kw)
  {
    KeyProximity p = new KeyProximity();
    float y = 0.f;
    for (KeyboardData.Row row : kw.rows)
    {
      y += row.shift;
      float key_y = y + row.height / 2.f;
      float x = 0.f;
      for (KeyboardData.Key key : row.keys)
      {
        x += key.shift;
        float key_x = x + key.width / 2.f;
        for (int dir = 0; dir < key.keys.length; dir++)
        {
          int c = char_of_key_value(key.keys[dir]);
          if (c != 0)
            p.add(c, key_x, key_y, dir);
        }
        x += key.width;
      }
      y += row.height;
    }
    return p;
  }

  /** Add a char on the key centered at [x, y], in key units. [dir] is the
      index in [KeyboardData.Key.keys], [0] for the center of the key. Used to
      build a layout. */
  public void add(int c, float x, float y, int dir)
  {
    c = normalize(c);
    Pos[] prev = _positions.get(c);
    int len = (prev == null) ? 0 : prev.length;
    Pos[] ps = new Pos[len + 1];
    for (int i = 0; i < len; i++)
      ps[i] = prev[i];
    ps[len] = new Pos(x, y, dir);
    _positions.put(c, ps);
  }

  /** Private */

  static final class Pos
  {
    final float x;
    final float y;
    final int dir;
    Pos(float x_, float y_, int d) { x = x_; y = y_; dir = d; }
  }

  final Map<Integer, Pos[]> _positions = new HashMap<Integer, Pos[]>();

  KeyProximity() {}

  static float pos_cost(Pos a, Pos b)
  {
    float dx = a.x - b.x;
    float dy = a.y - b.y;
    float dist = (float)Math.sqrt(dx * dx + dy * dy);
    if (dist < 0.01f) // Same key
    {
      if (a.dir == 0 || b.dir == 0)
        return SAME_KEY_CENTER;
      return dirs_adjacent(a.dir, b.dir) ?
        SAME_KEY_ADJACENT_DIR : SAME_KEY_OTHER_DIR;
    }
    if (dist <= NEIGHBOR_DISTANCE)
      return NEIGHBOR_KEY +
        ((a.dir == 0 && b.dir == 0) ? 0.f : NEIGHBOR_KEY_CORNER);
    return 1.f;
  }

  /** Directions around the center of a key in clockwise order. See
      [KeyboardData.Key.keys]. */
  static final int[] RING_POS = new int[]{
    /* 0 */ -1, /* 1 */ 0, /* 2 */ 2, /* 3 */ 6, /* 4 */ 4,
    /* 5 */ 7, /* 6 */ 3, /* 7 */ 1, /* 8 */ 5 };

  static boolean dirs_adjacent(int a, int b)
  {
    if (a >= RING_POS.length || b >= RING_POS.length)
      return false;
    int d = Math.abs(RING_POS[a] - RING_POS[b]);
    return d == 1 || d == 7;
  }

  static int char_of_key_value(KeyValue kv)
  {
    if (kv == null)
      return 0;
    switch (kv.getKind())
    {
      case Char:
        return kv.getChar();
      case String:
        String s = kv.getString();
        if (s.length() > 0 && s.codePointCount(0, s.length()) == 1)
          return s.codePointAt(0);
        return 0;
      default:
        return 0;
    }
  }

  static int normalize(int c)
  {
    c = Character.toLowerCase(c);
    if (c < 0x10000)
    {
      String s = CdictEngine.apply_substitutions(String.valueOf((char)c));
      c = s.charAt(0);
    }
    return c;
  }
}
