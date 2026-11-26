package juloo.keyboard2.suggestions;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import juloo.cdict.Cdict;

/** Offline evaluation of the suggestion ranking, see [run.sh].
    Compares the ranking before the typo model was introduced ([old_query])
    with [CdictEngine.query] on a QWERTY layout. */
public class Eval
{
  static final CdictEngine.UserWords NO_USER = new CdictEngine.UserWords() {
    public String find(String w) { return null; }
    public List<String> prefix_matches(String p, int m) { return new ArrayList<String>(); }
  };

  static final String[] ROWS = { "qwertyuiop", "asdfghjkl", "zxcvbnm" };
  static final float[] SHIFTS = { 0.f, 0.5f, 1.5f };

  static KeyProximity qwerty()
  {
    KeyProximity p = new KeyProximity();
    for (int r = 0; r < 3; r++)
      for (int i = 0; i < ROWS[r].length(); i++)
        p.add(ROWS[r].charAt(i), SHIFTS[r] + i + 0.5f, r + 0.5f, 0);
    p.add('\'', 1.5f + 6 + 0.5f, 2.5f, 3);
    return p;
  }

  /** Copy of the ranking before the typo model was introduced. */
  static String[] old_query(Cdict dict, String typed, int max)
  {
    Candidates c = new Candidates(max);
    String word = CdictEngine.apply_substitutions(typed);
    Cdict.Result r = dict.find(word);
    if (r.found) c.add(dict.word(r.index), 0);
    int[] suffixes = dict.suffixes(r, max);
    int[] dist = (word.length() < 3 || c.count + 1 >= max) ? new int[0] :
      dict.distance(word, 1, max);
    for (int j = 0; j < max && !c.is_full(); j++)
    {
      if (suffixes.length > j) c.add(dict.word(suffixes[j]), 0);
      if (dist.length > j) c.add(dict.word(dist[j]), 0);
    }
    return Arrays.copyOf(c.words, c.count);
  }

  static Cdict dict;
  static boolean DEBUG = System.getenv("DEBUG") != null;
  static int dbg = 0;
  static TypoModel typos = new TypoModel(qwerty());

  static String[] new_query(String typed, int max)
  {
    Candidates c = CdictEngine.query(dict, NO_USER, typos, typed, max);
    return Arrays.copyOf(c.words, c.count);
  }

  static int rank(String[] res, String target)
  {
    for (int i = 0; i < res.length; i++)
      if (res[i].equalsIgnoreCase(target)) return i;
    return -1;
  }

  static float[] pos(char c)
  {
    for (int r = 0; r < 3; r++)
    {
      int i = ROWS[r].indexOf(c);
      if (i >= 0) return new float[]{ SHIFTS[r] + i + 0.5f, r + 0.5f };
    }
    return null;
  }

  static char nearest(float x, float y)
  {
    char best = 0; float bd = Float.MAX_VALUE;
    for (int r = 0; r < 3; r++)
      for (int i = 0; i < ROWS[r].length(); i++)
      {
        float dx = SHIFTS[r] + i + 0.5f - x, dy = r + 0.5f - y;
        float d = dx * dx + dy * dy;
        if (d < bd) { bd = d; best = ROWS[r].charAt(i); }
      }
    return best;
  }

  /** Spatial noise + occasional omission, transposition, doubling. */
  static String noisy(String w, Random rnd, double sigma)
  {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < w.length(); i++)
    {
      char c = w.charAt(i);
      double p = rnd.nextDouble();
      if (p < 0.02) continue; // omission
      if (p < 0.04 && i + 1 < w.length()) { b.append(w.charAt(i + 1)).append(c); i++; continue; }
      float[] xy = pos(c);
      char t = (xy == null) ? c : nearest(xy[0] + (float)(rnd.nextGaussian() * sigma),
          xy[1] + (float)(rnd.nextGaussian() * sigma));
      b.append(t);
      if (p < 0.05) b.append(t); // doubling
    }
    return b.toString();
  }

  static String random_sub(String w, Random rnd)
  {
    int i = rnd.nextInt(w.length());
    char c;
    do { c = (char)('a' + rnd.nextInt(26)); } while (c == w.charAt(i));
    return w.substring(0, i) + c + w.substring(i + 1);
  }

  static boolean valid(String w)
  {
    return dict.find(CdictEngine.apply_substitutions(w)).found;
  }

  interface Gen { String gen(String w, Random rnd); }

  static void typo_eval(String name, List<String> targets, Gen g)
  {
    Random rnd = new Random(42);
    int n = 0; int[] o = new int[2], nw = new int[2];
    for (String t : targets)
    {
      if (t.length() < 3) continue;
      String typed = g.gen(t, rnd);
      if (typed.equals(t) || typed.length() < 2 || valid(typed)) continue;
      n++;
      int ro = rank(old_query(dict, typed, 3), t);
      int rn = rank(new_query(typed, 3), t);
      if (ro == 0) o[0]++; if (ro >= 0) o[1]++;
      if (rn == 0) nw[0]++; if (rn >= 0) nw[1]++;
    }
    System.out.printf("%-34s n=%5d  top1 %5.1f%% -> %5.1f%%   top3 %5.1f%% -> %5.1f%%%n",
        name, n, 100.*o[0]/n, 100.*nw[0]/n, 100.*o[1]/n, 100.*nw[1]/n);
  }

  static void prefix_eval(List<String> targets)
  {
    int n = 0; int[] o = new int[2], nw = new int[2];
    for (String t : targets)
      for (int k = 2; k < t.length(); k++)
      {
        String typed = t.substring(0, k);
        n++;
        int ro = rank(old_query(dict, typed, 3), t);
        int rn = rank(new_query(typed, 3), t);
        if (ro == 0) o[0]++; if (ro >= 0) o[1]++;
        if (rn == 0) nw[0]++; if (rn >= 0) nw[1]++;
        if (DEBUG && ro == 0 && rn != 0 && dbg++ < 25)
          System.out.println("  REGRESS " + typed + " -> " + t + ": old " + Arrays.toString(old_query(dict, typed, 3)) + " new " + Arrays.toString(new_query(typed, 3)));
      }
    System.out.printf("%-34s n=%5d  top1 %5.1f%% -> %5.1f%%   top3 %5.1f%% -> %5.1f%%%n",
        "clean prefix completion", n, 100.*o[0]/n, 100.*nw[0]/n, 100.*o[1]/n, 100.*nw[1]/n);
  }

  public static void main(String[] args) throws Exception
  {
    dict = Cdict.of_bytes(Files.readAllBytes(Paths.get(args[0])))[0];
    for (Cdict d : Cdict.of_bytes(Files.readAllBytes(Paths.get(args[0]))))
      if (d.name.equals("main")) dict = d;
    // Targets: most frequent words of the list that are in the dictionary,
    // repeated according to a Zipf-like weight (top words appear more).
    List<String> uniq = new ArrayList<String>();
    for (String line : Files.readAllLines(Paths.get(args[1])))
    {
      String w = line.split(" ")[0];
      if (w.matches("[a-z']+") && valid(w)) uniq.add(w);
      if (uniq.size() >= 3000) break;
    }
    List<String> targets = new ArrayList<String>();
    Random rnd = new Random(1);
    for (int i = 0; i < 20000; i++)
      targets.add(uniq.get((int)Math.min(uniq.size() - 1,
              Math.floor(Math.exp(rnd.nextDouble() * Math.log(uniq.size()))) - 1)));
    typo_eval("touch noise sigma=0.35 (realistic)", targets, (w, r) -> noisy(w, r, 0.35));
    typo_eval("touch noise sigma=0.5 (sloppy)", targets, (w, r) -> noisy(w, r, 0.5));
    typo_eval("1 random letter (not spatial)", targets, Eval::random_sub);
    prefix_eval(uniq.subList(0, 1000));
    // Timing
    String[] qs = new String[5000];
    Random r2 = new Random(7);
    for (int i = 0; i < qs.length; i++) qs[i] = noisy(targets.get(i), r2, 0.35);
    for (int warm = 0; warm < 2; warm++)
    {
      long t0 = System.nanoTime();
      for (String q : qs) if (q.length() >= 2) old_query(dict, q, 3);
      long t1 = System.nanoTime();
      for (String q : qs) if (q.length() >= 2) new_query(q, 3);
      long t2 = System.nanoTime();
      if (warm == 1)
        System.out.printf("avg per query: old %.1f us, new %.1f us%n",
            (t1 - t0) / 1e3 / qs.length, (t2 - t1) / 1e3 / qs.length);
    }
    if (args.length > 2)
      for (String q : args[2].split(","))
        System.out.println(q + ": old " + Arrays.toString(old_query(dict, q, 3))
            + "  new " + Arrays.toString(new_query(q, 3)));
  }
}
