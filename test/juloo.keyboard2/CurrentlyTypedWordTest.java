package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;

public class CurrentlyTypedWordTest
{
  public CurrentlyTypedWordTest() {}

  @Test
  public void context_words()
  {
    ctx("", false);
    ctx("hello ", false, "hello");
    ctx("say hello ", false, "say", "hello");
    ctx("I say hello ", false, "say", "hello");
    ctx("hello, ", false, "hello"); // Other separators are skipped
    ctx("I don't ", false, "I", "don't");
    // Stop at the beginning of the sentence
    ctx("Done. ", false);
    ctx("Done. Now ", false, "Now");
    ctx("Really?! ", false);
    ctx("first line\n", false);
    ctx("first line\nnext ", false, "next");
    // Truncated: the first word might be incomplete
    ctx("llo ", true);
    ctx("llo world ", true, "world");
    ctx(" world ", true, "world");
    // Surrogate pairs
    ctx("𝐀𝐁 ", false, "𝐀𝐁");
  }

  @Test
  public void tracks_context_while_typing()
  {
    CurrentlyTypedWord w = make();
    w.typed("I say");
    check(w, "say", "I");
    w.typed(" hel");
    check(w, "hel", "I", "say");
    w.typed("lo");
    check(w, "hello", "I", "say");
    w.typed(". ");
    check(w, "");
    w.typed("Now");
    check(w, "Now");
  }

  @Test
  public void delete_within_word()
  {
    CurrentlyTypedWord w = make();
    w.typed("say hello wo");
    w.remove_surrounding_text(1, 0);
    check(w, "w", "say", "hello");
  }

  @Test
  public void context_is_bounded()
  {
    CurrentlyTypedWord w = make();
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 20; i++)
      b.append("word").append(i).append(' ');
    w.typed(b.toString());
    assertTrue(w._before.length() <= CurrentlyTypedWord.CONTEXT_CHARS);
    assertArrayEquals(new String[]{ "word18", "word19" }, w.context());
  }

  static CurrentlyTypedWord make()
  {
    CurrentlyTypedWord w = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback() {
          public void currently_typed_word(String word) {}
        });
    w._enabled = true;
    return w;
  }

  static void check(CurrentlyTypedWord w, String word, String... context)
  {
    assertEquals(word, w.get());
    assertArrayEquals(context, w.context());
  }

  static void ctx(String text, boolean truncated, String... expected)
  {
    assertArrayEquals(text, expected,
        CurrentlyTypedWord.context_words(text, truncated, 2));
  }
}
