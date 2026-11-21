package juloo.keyboard2;

import org.junit.Test;
import static juloo.keyboard2.AutoClosePairs.*;
import static org.junit.Assert.*;

public class AutoClosePairsTest
{
  public AutoClosePairsTest() {}

  @Test
  public void closing()
  {
    assertEquals(')', closing_of('('));
    assertEquals(']', closing_of('['));
    assertEquals('}', closing_of('{'));
    assertEquals('"', closing_of('"'));
    assertEquals('»', closing_of('«'));
    assertEquals(0, closing_of('a'));
    assertEquals(0, closing_of(')'));
  }

  @Test
  public void close()
  {
    assertTrue(should_close('(', (char)0, (char)0));
    assertTrue(should_close('(', 'f', (char)0)); // Function call
    assertTrue(should_close('[', ' ', ' '));
    assertTrue(should_close('(', '(', ')')); // Nested
    assertTrue(should_close('"', ' ', '\n'));
    assertFalse(should_close('(', ' ', 'a')); // Before a word
    assertFalse(should_close('\'', 'n', (char)0)); // Apostrophe
    assertFalse(should_close('"', 'o', ' ')); // Closing quote
    assertFalse(should_close('a', ' ', ' '));
  }

  @Test
  public void overtype()
  {
    assertTrue(should_overtype(')', ')'));
    assertTrue(should_overtype('"', '"'));
    assertFalse(should_overtype(')', ']'));
    assertFalse(should_overtype(')', (char)0));
    assertFalse(should_overtype('a', 'a'));
  }

  @Test
  public void delete_pair()
  {
    assertTrue(should_delete_pair('(', ')'));
    assertTrue(should_delete_pair('\'', '\''));
    assertFalse(should_delete_pair('(', ']'));
    assertFalse(should_delete_pair(')', '('));
    assertFalse(should_delete_pair((char)0, ')'));
  }
}
