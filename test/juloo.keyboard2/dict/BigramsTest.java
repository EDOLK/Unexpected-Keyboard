package juloo.keyboard2.dict;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BigramsTest
{
  public BigramsTest() {}

  /** Built with [scripts/gen_bigrams.py] from [test/fixtures/small.combined]. */
  public static Bigrams load_fixture() throws Exception
  {
    return Bigrams.of_bytes(
        Files.readAllBytes(Paths.get("test/fixtures/small.bigrams")));
  }

  @Test
  public void successors() throws Exception
  {
    Bigrams b = load_fixture();
    assertEquals("test_dict", b.dict_name);
    // thank, goodness, God, you, I, i, mean, think, café, crème
    assertEquals(10, b.size());
    succ(b, "thank", new String[]{ "you", "God", "goodness" }, 3, 2, 1);
    succ(b, "I", new String[]{ "think", "mean" }, 3, 1);
    succ(b, "café", new String[]{ "crème" }, 2);
    // Not found
    succ(b, "you", new String[]{});
    succ(b, "unknown", new String[]{});
    succ(b, "", new String[]{});
    // Falls back to lowercase
    succ(b, "Thank", new String[]{ "you", "God", "goodness" }, 3, 2, 1);
    // Exact match first
    succ(b, "i", new String[]{ "mean" }, 1);
  }

  @Test
  public void invalid_files() throws Exception
  {
    byte[] good = Files.readAllBytes(Paths.get("test/fixtures/small.bigrams"));
    invalid(new byte[0]);
    invalid("not a bigrams file".getBytes());
    invalid(Arrays.copyOf(good, good.length - 1));
    invalid(Arrays.copyOf(good, 20));
    byte[] bad_version = good.clone();
    bad_version[4] = 2;
    invalid(bad_version);
    // Corrupt the last successor's word index
    byte[] bad_index = good.clone();
    bad_index[bad_index.length - 4] = (byte)0xFF;
    invalid(bad_index);
  }

  static void succ(Bigrams b, String prev, String[] words, int... weights)
  {
    Bigrams.Successors s = b.successors(prev);
    assertArrayEquals(prev, words, s.words);
    assertArrayEquals(prev, weights, s.weights);
  }

  static void invalid(byte[] data)
  {
    try
    {
      Bigrams.of_bytes(data);
      fail("Expected an error");
    }
    catch (Bigrams.Error e) {}
  }
}
