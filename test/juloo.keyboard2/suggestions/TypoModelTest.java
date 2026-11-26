package juloo.keyboard2.suggestions;

import org.junit.Test;
import static org.junit.Assert.*;

public class TypoModelTest
{
  public TypoModelTest() {}

  /** The letters of the QWERTY (US) layout, with the apostrophe on the
      south-west corner of 'm', like in [latn_qwerty_us.xml]. */
  static KeyProximity qwerty()
  {
    KeyProximity p = new KeyProximity();
    String[] rows = { "qwertyuiop", "asdfghjkl", "zxcvbnm" };
    float[] shifts = { 0.f, 0.5f, 1.5f };
    for (int r = 0; r < rows.length; r++)
      for (int i = 0; i < rows[r].length(); i++)
        p.add(rows[r].charAt(i), shifts[r] + i + 0.5f, r + 0.5f, 0);
    p.add('\'', 1.5f + 6 + 0.5f, 2.5f, 3);
    return p;
  }

  static final float EPS = 0.001f;

  @Test
  public void substitution_cost()
  {
    KeyProximity p = qwerty();
    assertEquals(0.f, p.substitution_cost('a', 'a'), EPS);
    assertEquals(0.f, p.substitution_cost('A', 'a'), EPS);
    assertEquals(KeyProximity.NEIGHBOR_KEY, p.substitution_cost('q', 'w'), EPS);
    // Diagonal neighbors on staggered rows
    assertEquals(KeyProximity.NEIGHBOR_KEY, p.substitution_cost('w', 'a'), EPS);
    assertEquals(KeyProximity.NEIGHBOR_KEY, p.substitution_cost('a', 'w'), EPS);
    assertEquals(1.f, p.substitution_cost('q', 'p'), EPS);
    assertEquals(1.f, p.substitution_cost('q', 'd'), EPS);
    // Missed swipe on the same key
    assertEquals(KeyProximity.SAME_KEY_CENTER, p.substitution_cost('m', '\''), EPS);
    // Unknown chars
    assertEquals(1.f, p.substitution_cost('a', '7'), EPS);
    assertEquals(1.f, KeyProximity.EMPTY.substitution_cost('q', 'w'), EPS);
  }

  @Test
  public void dirs_adjacent()
  {
    assertTrue(KeyProximity.dirs_adjacent(1, 7));
    assertTrue(KeyProximity.dirs_adjacent(1, 5));
    assertTrue(KeyProximity.dirs_adjacent(4, 6));
    assertTrue(KeyProximity.dirs_adjacent(3, 8));
    assertFalse(KeyProximity.dirs_adjacent(5, 7));
    assertFalse(KeyProximity.dirs_adjacent(1, 4));
  }

  @Test
  public void cost()
  {
    TypoModel t = new TypoModel(qwerty());
    cost(t, 0.f, "hello", "hello");
    cost(t, 0.f, "Hello", "hello");
    cost(t, 0.f, "cafe", "café"); // Diacritics
    cost(t, TypoModel.COMPLETION, "hel", "hello");
    cost(t, TypoModel.TRANSPOSE, "teh", "the");
    cost(t, TypoModel.DOUBLE, "helo", "hello");
    cost(t, TypoModel.DOUBLE, "helllo", "hello");
    cost(t, TypoModel.APOSTROPHE, "dont", "don't");
    cost(t, KeyProximity.NEIGHBOR_KEY, "hrllo", "hello");
    cost(t, 1.f, "hpllo", "hello");
    cost(t, TypoModel.EXTRA_CHAR, "hellio", "hello");
    // Correction and completion
    cost(t, KeyProximity.NEIGHBOR_KEY + TypoModel.COMPLETION, "hrl", "hello");
  }

  @Test
  public void neighbor_is_cheaper_than_far_key()
  {
    TypoModel t = new TypoModel(qwerty());
    assertTrue(t.cost("tge", "the") < t.cost("tpe", "the"));
  }

  static void cost(TypoModel t, float expected, String typed, String word)
  {
    assertEquals(typed + " -> " + word, expected, t.cost(typed, word), EPS);
  }
}
