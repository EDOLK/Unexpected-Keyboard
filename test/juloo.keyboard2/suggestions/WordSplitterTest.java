package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import juloo.keyboard2.dict.Bigrams;
import juloo.keyboard2.dict.UserBigrams;
import org.junit.Test;
import static org.junit.Assert.*;

public class WordSplitterTest
{
  public WordSplitterTest() {}

  /** Dictionary of "word=freq" entries, matched ignoring case like Cdict
      does, and word pairs as "prev>next". */
  static final class FakeLexicon implements WordSplitter.Lexicon
  {
    final Map<String, WordSplitter.Word> words =
      new HashMap<String, WordSplitter.Word>();
    final List<WordSplitter.Word> ordered = new ArrayList<WordSplitter.Word>();
    final Map<String, List<String>> pairs = new HashMap<String, List<String>>();

    FakeLexicon(String... entries)
    {
      for (String e : entries)
      {
        int p = e.indexOf('>');
        if (p > 0)
        {
          String prev = e.substring(0, p);
          if (!pairs.containsKey(prev))
            pairs.put(prev, new ArrayList<String>());
          pairs.get(prev).add(e.substring(p + 1));
          continue;
        }
        String[] kv = e.split("=");
        WordSplitter.Word w =
          new WordSplitter.Word(kv[0], Integer.parseInt(kv[1]));
        words.put(kv[0].toLowerCase(), w);
        ordered.add(w);
      }
    }

    public WordSplitter.Word find(String w)
    { return words.get(w.toLowerCase()); }

    public List<WordSplitter.Word> completions(String prefix, int max)
    {
      List<WordSplitter.Word> res = new ArrayList<WordSplitter.Word>();
      String p = prefix.toLowerCase();
      for (WordSplitter.Word w : ordered)
      {
        String l = w.word.toLowerCase();
        if (res.size() < max && l.startsWith(p) && !l.equals(p))
          res.add(w);
      }
      return res;
    }

    public Bigrams.Successors successors(String prev)
    {
      List<String> next = pairs.get(prev);
      List<UserBigrams.Pair> learned = new ArrayList<UserBigrams.Pair>();
      if (next != null)
        for (String n : next)
          learned.add(new UserBigrams.Pair(n, 5));
      return UserBigrams.merge(null, learned, prev);
    }
  }

  static final FakeLexicon LEX = new FakeLexicon(
      "it's=14", "its=14", "mine=13", "min=12", "mind=12",
      "don't=14", "know=13", "thank=12", "you=14", "you're=13",
      "in=14", "the=15", "TT=13", "hat=13", "that=14", "NTH=12",
      "I=14", "a=14", "x=9", "can=14", "ts=11",
      "it's>mine", "thank>you", "I>can");

  static String best(String typed)
  {
    List<WordSplitter.Split> s =
      WordSplitter.split(LEX, Bigrams.NONE, typed, 3);
    return s.isEmpty() ? null : s.get(0).text();
  }

  static List<String> all(String typed)
  {
    List<String> res = new ArrayList<String>();
    for (WordSplitter.Split s : WordSplitter.split(LEX, Bigrams.NONE, typed, 3))
      res.add(s.text());
    return res;
  }

  @Test
  public void two_words()
  {
    assertEquals("in the", best("inthe"));
    assertEquals("thank you", best("thankyou"));
    assertEquals("I can", best("ican"));
  }

  @Test
  public void apostrophe()
  {
    assertEquals("don't know", best("dontknow"));
    // Word pairs choose between "its" and "it's".
    assertEquals("it's mine", best("itsmine"));
  }

  @Test
  public void last_word_completed()
  {
    assertEquals("thank you", best("thankyo"));
    assertTrue(all("itsmin").contains("it's mine"));
  }

  @Test
  public void three_words()
  {
    assertEquals("I can thank", best("icanthank"));
  }

  @Test
  public void abbreviations_need_capitals()
  {
    // "TT hat" and "I NTH e" must not be found.
    assertNull(best("tthat"));
    for (String s : all("inthe"))
      assertFalse(s, s.contains("NTH"));
    assertEquals("TT hat", best("TThat"));
  }

  @Test
  public void rare_short_words_rejected()
  {
    // "x" is too rare for a one-letter word, "ts" for a two-letter word.
    assertNull(best("xcan"));
    assertNull(best("tscan"));
  }

  @Test
  public void not_splittable()
  {
    assertNull(best("ina")); // Too short
    assertNull(best("in1the"));
    assertNull(best("qqqqqq"));
  }

  @Test
  public void case_matches()
  {
    assertTrue(WordSplitter.case_matches("the", "the"));
    assertTrue(WordSplitter.case_matches("i", "I"));
    assertTrue(WordSplitter.case_matches("im", "I'm"));
    assertTrue(WordSplitter.case_matches("The", "the"));
    assertFalse(WordSplitter.case_matches("tt", "TT"));
    assertFalse(WordSplitter.case_matches("ryan", "Ryan"));
    assertTrue(WordSplitter.case_matches("Ryan", "Ryan"));
    // Completion
    assertFalse(WordSplitter.case_matches("ry", "Ryan"));
    assertFalse(WordSplitter.case_matches("Ry", "RYAN"));
  }
}
