package juloo.keyboard2.suggestions;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Bigrams;

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
    return new_query(Bigrams.NONE, typed, max);
  }

  static String[] new_query(Bigrams.Successors succ, String typed, int max)
  {
    Candidates c = CdictEngine.query(dict, NO_USER, typos, succ, pairs,
        typed, max);
    return Arrays.copyOf(c.words, c.count);
  }

  /** Used to split words with missing spaces. */
  static CdictEngine.Pairs pairs = CdictEngine.NO_PAIRS;

  /** Words typed without the spaces between them. Runs of 2 and 3 words are
      taken from running text. The last word might be incomplete. Also
      measures how often a split is wrongly suggested first for a single
      word with a typo. */
  static void split_eval(String[] files) throws IOException
  {
    List<String[]> runs = new ArrayList<String[]>();
    for (String f : files)
    {
      String text = new String(Files.readAllBytes(Paths.get(f)), "UTF-8");
      for (String sentence : text.split("[.!?;:\"(),]|\\R\\s*\\R"))
      {
        List<String> ws = new ArrayList<String>();
        for (String w : sentence.split("[^A-Za-z']+"))
        {
          w = w.replaceAll("^'+|'+$", "");
          if (!w.isEmpty()) ws.add(w);
        }
        for (int k = 2; k <= 3; k++)
          for (int i = 0; i + k <= ws.size(); i += k)
          {
            boolean ok = true;
            for (int j = i; j < i + k; j++) ok &= valid(ws.get(j));
            if (ok) runs.add(ws.subList(i, i + k).toArray(new String[0]));
          }
      }
    }
    Collections.shuffle(runs, new Random(5));
    for (int variant = 0; variant < 3; variant++)
    {
      int n = 0, top1 = 0, top3 = 0, n_dbg = 0;
      for (String[] r : runs)
      {
        if (n >= 5000) break;
        String joined = String.join("", r).replace("'", "");
        if (variant == 1)
        {
          // The last word is not fully typed
          String last = r[r.length - 1];
          if (last.length() < 3) continue;
          joined = joined.substring(0, joined.length() - 1);
        }
        if (variant == 2) joined = joined.toLowerCase();
        if (joined.length() < WordSplitter.MIN_LENGTH || valid(joined)) continue;
        n++;
        String target = String.join(" ", r);
        String[] res = new_query(joined, 3);
        int rk = rank(res, target);
        if (rk == 0) top1++; if (rk >= 0) top3++;
        if (DEBUG && rk != 0 && n_dbg++ < 15)
          System.out.println("  MISS " + joined + " -> " + target + ": " + Arrays.toString(res));
      }
      String name = (variant == 0) ? "missing spaces" :
        (variant == 1) ? "missing spaces, last incomplete" :
        "missing spaces, lowercase";
      System.out.printf("%-34s n=%5d  top1 %5.1f%%           top3 %5.1f%%%n",
          name, n, 100.*top1/n, 100.*top3/n);
    }
  }

  /** How often a single word with a typo is replaced by a split on space. */
  static void split_false_positives(List<String> targets)
  {
    Random rnd = new Random(42);
    int n = 0, fp = 0, n_dbg = 0;
    for (String t : targets)
    {
      if (t.length() < 3) continue;
      String typed = noisy(t, rnd, 0.35);
      if (typed.equals(t) || typed.length() < WordSplitter.MIN_LENGTH || valid(typed)) continue;
      n++;
      String[] res = new_query(typed, 3);
      if (res.length > 0 && res[0].indexOf(' ') >= 0)
      {
        fp++;
        if (DEBUG && n_dbg++ < 15)
          System.out.println("  FALSE SPLIT " + typed + " (" + t + "): " + Arrays.toString(res));
      }
    }
    System.out.printf("%-34s n=%5d  %5.2f%%%n", "typo replaced by a split", n, 100.*fp/n);
  }

  static Bigrams bigrams = null;
  /** When not [null], learned pairs are merged with [bigrams]. */
  static juloo.keyboard2.dict.UserBigrams learned = null;

  /** Pairs of (previous word, word) from running text, within sentences. */
  static List<String[]> corpus_pairs(String[] files) throws IOException
  {
    List<String[]> pairs = ordered_pairs(files);
    Collections.shuffle(pairs, new Random(3));
    return pairs.subList(0, Math.min(pairs.size(), 20000));
  }

  /** In the order of the text. */
  static List<String[]> ordered_pairs(String[] files) throws IOException
  {
    List<String[]> pairs = new ArrayList<String[]>();
    for (String f : files)
    {
      String text = new String(Files.readAllBytes(Paths.get(f)), "UTF-8");
      for (String sentence : text.split("[.!?;:\"()]|\\R\\s*\\R"))
      {
        String prev = null;
        for (String w : sentence.split("[^A-Za-z']+"))
        {
          w = w.replaceAll("^'+|'+$", "");
          if (w.isEmpty()) continue;
          if (prev != null && valid(w) && valid(prev))
            pairs.add(new String[]{ prev, w });
          prev = w;
        }
      }
    }
    return pairs;
  }

  static final class MemStorage implements juloo.keyboard2.dict.UserBigrams.Storage
  {
    String data = null;
    public String read() { return data; }
    public void write(String d) { data = d; }
  }

  /** Learn from the first 60% of a book and evaluate on the rest. */
  static void learning_eval(String book) throws IOException
  {
    List<String[]> pairs = ordered_pairs(new String[]{ book });
    int split = pairs.size() * 6 / 10;
    juloo.keyboard2.dict.UserBigrams ub =
      new juloo.keyboard2.dict.UserBigrams(new MemStorage());
    for (String[] p : pairs.subList(0, split))
      ub.learn(p[0], p[1]);
    List<String[]> test = new ArrayList<String[]>(pairs.subList(split, pairs.size()));
    Collections.shuffle(test, new Random(3));
    test = test.subList(0, Math.min(test.size(), 20000));
    System.out.printf("Learned %d pairs (%d kept) from the first 60%% of %s, testing on the rest.%n",
        split, ub.size(), Paths.get(book).getFileName());
    System.out.println("Word pairs file only (without -> with context):");
    learned = null;
    context_eval(test);
    System.out.println("Word pairs file + learned pairs (without -> with context):");
    learned = ub;
    context_eval(test);
    learned = null;
  }

  static void context_eval(List<String[]> pairs)
  {
    // Next word prediction, before typing anything.
    {
      int n = 0, top1 = 0, top3 = 0;
      for (String[] p : pairs)
      {
        n++;
        Candidates c = CdictEngine.predict(succ_of(p[0]), 3);
        int r = rank(Arrays.copyOf(c.words, c.count), p[1]);
        if (r == 0) top1++; if (r >= 0) top3++;
      }
      System.out.printf("%-34s n=%5d  top1 %5.1f%%           top3 %5.1f%%%n",
          "next word, nothing typed", n, 100.*top1/n, 100.*top3/n);
    }
    // Completion and correction, without -> with the previous word.
    for (int k = 1; k <= 3; k++)
    {
      int n = 0; int[] o = new int[2], nw = new int[2];
      for (String[] p : pairs)
      {
        if (p[1].length() <= k) continue;
        n++;
        String typed = p[1].substring(0, k);
        int ro = rank(new_query(typed, 3), p[1]);
        int rn = rank(new_query(succ_of(p[0]), typed, 3), p[1]);
        if (ro == 0) o[0]++; if (ro >= 0) o[1]++;
        if (rn == 0) nw[0]++; if (rn >= 0) nw[1]++;
      }
      print("completion, " + k + " letter(s), +context", n, o, nw);
    }
    Random rnd = new Random(42);
    int n = 0; int[] o = new int[2], nw = new int[2];
    for (String[] p : pairs)
    {
      if (p[1].length() < 3) continue;
      String typed = noisy(p[1], rnd, 0.35);
      if (typed.equals(p[1]) || typed.length() < 2 || valid(typed)) continue;
      n++;
      int ro = rank(new_query(typed, 3), p[1]);
      int rn = rank(new_query(succ_of(p[0]), typed, 3), p[1]);
      if (ro == 0) o[0]++; if (ro >= 0) o[1]++;
      if (rn == 0) nw[0]++; if (rn >= 0) nw[1]++;
    }
    print("typos (sigma=0.35), +context", n, o, nw);
  }

  static Bigrams.Successors succ_of(String prev)
  {
    String[] ctx = new String[]{ prev };
    return (learned == null) ? CdictEngine.successors(bigrams, ctx)
      : learned.merge(bigrams, prev);
  }

  static void print(String name, int n, int[] o, int[] nw)
  {
    System.out.printf("%-34s n=%5d  top1 %5.1f%% -> %5.1f%%   top3 %5.1f%% -> %5.1f%%%n",
        name, n, 100.*o[0]/n, 100.*nw[0]/n, 100.*o[1]/n, 100.*nw[1]/n);
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

  public static void main(String[] argv) throws Exception
  {
    // Options: --bigrams FILE --corpus FILE,FILE
    List<String> rest = new ArrayList<String>();
    String[] corpus = null;
    for (int i = 0; i < argv.length; i++)
    {
      if (argv[i].equals("--bigrams"))
        bigrams = Bigrams.of_bytes(Files.readAllBytes(Paths.get(argv[++i])));
      else if (argv[i].equals("--corpus"))
        corpus = argv[++i].split(",");
      else
        rest.add(argv[i]);
    }
    String[] args = rest.toArray(new String[0]);
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
    if (bigrams != null) pairs = CdictEngine.pairs_of(bigrams);
    typo_eval("touch noise sigma=0.35 (realistic)", targets, (w, r) -> noisy(w, r, 0.35));
    typo_eval("touch noise sigma=0.5 (sloppy)", targets, (w, r) -> noisy(w, r, 0.5));
    typo_eval("1 random letter (not spatial)", targets, Eval::random_sub);
    prefix_eval(uniq.subList(0, 1000));
    split_false_positives(targets);
    if (corpus != null) split_eval(corpus);
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
    if (bigrams != null && corpus != null)
    {
      System.out.println("With word pairs, on running text (without -> with context):");
      context_eval(corpus_pairs(corpus));
      learning_eval(corpus[0]);
    }
    if (args.length > 2)
      for (String q : args[2].split(","))
        System.out.println(q + ": old " + Arrays.toString(old_query(dict, q, 3))
            + "  new " + Arrays.toString(new_query(q, 3)));
  }
}
