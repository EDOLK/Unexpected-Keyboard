package juloo.keyboard2;

/** Decisions for the "auto-close pairs" option: inserting the matching
    closing character when an opening one is typed, typing over an existing
    closing character and deleting both characters of an empty pair with
    backspace. Pure functions, the editor interactions are done in
    [KeyEventHandler]. */
public final class AutoClosePairs
{
  static final String OPENING = "([{\"'`“‘«";
  static final String CLOSING = ")]}\"'`”’»";

  /** The closing character matching [c] or [0] if [c] doesn't open a
      pair. */
  public static char closing_of(char c)
  {
    int i = OPENING.indexOf(c);
    return (i < 0) ? 0 : CLOSING.charAt(i);
  }

  public static boolean is_closing(char c)
  {
    return CLOSING.indexOf(c) >= 0;
  }

  /** Symmetrical pairs, like quotes, use the same character to open and
      close. */
  static boolean is_symmetrical(char c)
  {
    return closing_of(c) == c;
  }

  /** Whether typing [c] should also insert its closing character. [before] and
      [after] are the characters around the cursor, [0] at the edges of the
      text or if unknown. */
  public static boolean should_close(char c, char before, char after)
  {
    if (closing_of(c) == 0)
      return false;
    // Don't add a pair when typing in front of a word.
    if (after != 0 && !Character.isWhitespace(after) && !is_closing(after))
      return false;
    // Quotes in the middle or at the end of a word are likely apostrophes or
    // closing quotes.
    if (is_symmetrical(c) && before != 0 && Character.isLetterOrDigit(before))
      return false;
    return true;
  }

  /** Whether typing [c] should move over the identical character after the
      cursor instead of inserting a new one. */
  public static boolean should_overtype(char c, char after)
  {
    return after != 0 && c == after && is_closing(c);
  }

  /** Whether backspace should delete both characters around the cursor. */
  public static boolean should_delete_pair(char before, char after)
  {
    char close = closing_of(before);
    return close != 0 && close == after;
  }
}
