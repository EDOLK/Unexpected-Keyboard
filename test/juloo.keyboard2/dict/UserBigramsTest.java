package juloo.keyboard2.dict;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class UserBigramsTest
{
  public UserBigramsTest() {}

  static final class MemStorage implements UserBigrams.Storage
  {
    String data = null;
    boolean locked = false;
    int writes = 0;
    public String read() throws IOException
    {
      if (locked) throw new IOException("locked");
      return data;
    }
    public void write(String d) { data = d; writes++; }
  }

  @Test
  public void learn_and_successors()
  {
    UserBigrams ub = new UserBigrams(new MemStorage());
    ub.learn("thank", "you");
    ub.learn("Thank", "you");
    ub.learn("thank", "goodness");
    List<UserBigrams.Pair> s = ub.successors("THANK");
    assertEquals(2, s.size());
    assertEquals("you", s.get(0).word);
    assertEquals(2, s.get(0).count);
    assertEquals("goodness", s.get(1).word);
    assertEquals(1, s.get(1).count);
    assertEquals(0, ub.successors("unknown").size());
    assertEquals(2, ub.size());
  }

  @Test
  public void merge() throws Exception
  {
    Bigrams b = BigramsTest.load_fixture();
    UserBigrams ub = new UserBigrams(new MemStorage());
    // Without learned pairs: base pairs only, normalized.
    Bigrams.Successors s = ub.merge(b, "thank");
    assertArrayEquals(new String[]{ "you", "God", "goodness" }, s.words);
    assertEquals(UserBigrams.BASE_MAX_WEIGHT, s.weights[0]);
    // Learned pairs come first, a pair in both gets more weight.
    ub.learn("thank", "goodness");
    ub.learn("thank", "heavens");
    for (int i = 0; i < UserBigrams.LEARNED_SATURATION; i++)
      ub.learn("thank", "you");
    s = ub.merge(b, "thank");
    assertArrayEquals(new String[]{ "you", "goodness", "heavens", "God" },
        s.words);
    assertEquals(Bigrams.MAX_WEIGHT, s.weights[0]);
    assertTrue(s.weights[1] > s.weights[2]); // goodness is also in the base
    assertTrue(s.weights[2] >= UserBigrams.LEARNED_MIN_WEIGHT);
    assertTrue(s.weights[3] <= UserBigrams.BASE_MAX_WEIGHT);
    // No base
    s = ub.merge(null, "thank");
    assertArrayEquals(new String[]{ "you", "goodness", "heavens" }, s.words);
    // A pair typed once isn't as strong as a pair typed many times.
    UserBigrams ub2 = new UserBigrams(new MemStorage());
    ub2.learn("a", "b");
    assertTrue(ub2.merge(null, "a").weights[0] < Bigrams.MAX_WEIGHT);
  }

  @Test
  public void persistence() throws Exception
  {
    MemStorage st = new MemStorage();
    UserBigrams ub = new UserBigrams(st);
    ub.learn("thank", "you");
    ub.learn("thank", "you");
    ub.learn("I", "think");
    ub.save();
    Thread.sleep(200); // Saved in the background
    assertEquals(1, st.writes);
    ub.save(); // Nothing changed
    Thread.sleep(100);
    assertEquals(1, st.writes);
    UserBigrams loaded = new UserBigrams(st);
    assertEquals(2, loaded.size());
    assertEquals(2, loaded.successors("thank").get(0).count);
    assertEquals("think", loaded.successors("i").get(0).word);
    // Invalid lines are ignored
    st.data = "a\tb\t3\nbroken\nc\td\tx\n\t\t1\ne\tf\t-1\n";
    assertEquals(1, new UserBigrams(st).size());
  }

  @Test
  public void locked_storage() throws Exception
  {
    MemStorage st = new MemStorage();
    st.data = "thank\tyou\t4\n";
    st.locked = true;
    UserBigrams ub = new UserBigrams(st);
    // Nothing is learned or saved while locked, which would overwrite the
    // stored pairs.
    ub.learn("hello", "world");
    assertEquals(0, ub.successors("thank").size());
    ub.save();
    Thread.sleep(100);
    assertEquals(0, st.writes);
    st.locked = false;
    assertEquals(4, ub.successors("thank").get(0).count);
    assertEquals(0, ub.successors("hello").size());
  }

  @Test
  public void eviction()
  {
    UserBigrams ub = new UserBigrams(new MemStorage());
    for (int i = 0; i < 10; i++)
      ub.learn("frequent", "pair");
    for (int i = 0; i <= UserBigrams.MAX_PAIRS; i++)
      ub.learn("w" + i, "x");
    assertTrue(ub.size() <= UserBigrams.MAX_PAIRS);
    assertTrue(ub.size() >= UserBigrams.MAX_PAIRS * 9 / 10);
    assertEquals(10, ub.successors("frequent").get(0).count);
  }

  @Test
  public void clear()
  {
    MemStorage st = new MemStorage();
    UserBigrams ub = new UserBigrams(st);
    ub.learn("thank", "you");
    ub.clear();
    assertEquals(0, ub.size());
    assertEquals(0, ub.successors("thank").size());
  }

  @Test
  public void clear_before_loading() throws Exception
  {
    MemStorage st = new MemStorage();
    st.data = "thank\tyou\t4\n";
    new UserBigrams(st).clear();
    Thread.sleep(200); // Saved in the background
    assertEquals(0, new UserBigrams(st).size());
  }
}
