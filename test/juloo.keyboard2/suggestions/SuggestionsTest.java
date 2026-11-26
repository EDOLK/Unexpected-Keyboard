package juloo.keyboard2.suggestions;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuggestionsTest
{
  public SuggestionsTest() {}

  @Test
  public void candidates_add()
  {
    Candidates c = new Candidates(3);
    assertTrue(c.add("hello", 0));
    assertFalse(c.add(null, -1));
    assertFalse(c.add("Hello", -1)); // Duplicate, ignoring case
    assertTrue(c.add("help", -1));
    assertTrue(c.add("helm", -2));
    assertTrue(c.is_full());
    assertFalse(c.add("hell", -3));
    assertEquals(3, c.count);
    assertArrayEquals(new String[]{ "hello", "help", "helm" }, c.words);
  }

  @Test
  public void known_word()
  {
    Suggestions s = set("hel", true, "hello", "help");
    assertEquals(2, s.count);
    assertArrayEquals(new String[]{ "hello", "help", null }, s.suggestions);
    assertNull(s.unknown_word);
  }

  @Test
  public void capitalized()
  {
    Suggestions s = set("Hel", true, "hello", "help");
    assertArrayEquals(new String[]{ "Hello", "Help", null }, s.suggestions);
  }

  @Test
  public void unknown_word_appended()
  {
    Suggestions s = set("helq", false, "hello");
    assertEquals(2, s.count);
    assertArrayEquals(new String[]{ "hello", "helq", null }, s.suggestions);
    assertEquals("helq", s.unknown_word);
  }

  @Test
  public void unknown_word_takes_last_slot()
  {
    Suggestions s = set("Helq", false, "hello", "help", "helm");
    assertEquals(3, s.count);
    // The typed word is not capitalized again.
    assertArrayEquals(new String[]{ "Hello", "Help", "Helq" }, s.suggestions);
    assertEquals("Helq", s.unknown_word);
  }

  @Test
  public void engine_returns_too_many()
  {
    Suggestions s = set("a", true, "a", "b", "c", "d");
    assertEquals(Suggestions.MAX_COUNT, s.count);
    assertArrayEquals(new String[]{ "a", "b", "c" }, s.suggestions);
  }

  static Suggestions set(String typed, boolean valid, String... words)
  {
    Candidates c = new Candidates(words.length);
    for (String w : words)
      c.add(w, -c.count);
    c.typed_word_valid = valid;
    Suggestions s = new Suggestions(null, null, null, null);
    s.set_candidates(c, typed);
    return s;
  }
}
