import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.InlineKeyDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.PersistentMapBuilder;
import com.intellij.util.io.PersistentMapImpl;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Mimics the real {@code com.intellij.psi.impl.cache.impl.id.IdIndex}: keys are word-hash-derived
 * {@link IdIndexEntry}s stored via {@link InlineKeyDescriptor}, values are single-byte occurrence
 * bitmasks mirroring {@code com.intellij.psi.search.UsageSearchContext} (not a real dependency here).
 */
public class Main {
  private static final int IN_CODE = 0x1;
  private static final int IN_COMMENTS = 0x2;
  private static final int IN_STRINGS = 0x4;
  private static final int IN_FOREIGN_LANGUAGES = 0x8;
  private static final int IN_PLAIN_TEXT = 0x10;

  private static final KeyDescriptor<IdIndexEntry> KEY_DESCRIPTOR = new InlineKeyDescriptor<>() {
    @Override
    public IdIndexEntry fromInt(int n) {
      return new IdIndexEntry(n);
    }

    @Override
    public int toInt(IdIndexEntry entry) {
      return entry.wordHashCode;
    }
  };

  private static final DataExternalizer<Integer> VALUE_EXTERNALIZER = new DataExternalizer<>() {
    @Override
    public void save(DataOutput out, Integer value) throws IOException {
      out.write(value & 0xFF);
    }

    @Override
    public Integer read(DataInput in) throws IOException {
      return in.readByte() & 0xFF;
    }
  };

  public static void main(String[] args) throws Exception {
    int entryCount = parseEntryCount(args);

    Map<IdIndexEntry, Integer> data = generateSyntheticIndex(entryCount);

    Path file = Files.createTempFile("id-index-demo", "");
    Files.deleteIfExists(file);

    // 1. populate and flush/close
    PersistentMapImpl<IdIndexEntry, Integer> map =
        new PersistentMapImpl<>(PersistentMapBuilder.newBuilder(file, KEY_DESCRIPTOR, VALUE_EXTERNALIZER));
    long writeStart = System.nanoTime();
    try {
      for (Map.Entry<IdIndexEntry, Integer> entry : data.entrySet()) {
        map.put(entry.getKey(), entry.getValue());
      }
      map.force();
    } finally {
      map.close();
    }
    long writeMillis = (System.nanoTime() - writeStart) / 1_000_000;
    System.out.printf("Wrote %d entries in %d ms (%.0f entries/sec)%n",
        entryCount, writeMillis, ratePerSec(entryCount, writeMillis));

    // 2. reopen from the SAME file path -- proves the round-trip through disk
    PersistentMapImpl<IdIndexEntry, Integer> reopened =
        new PersistentMapImpl<>(PersistentMapBuilder.newBuilder(file, KEY_DESCRIPTOR, VALUE_EXTERNALIZER));
    int mismatches = 0;
    long readStart = System.nanoTime();
    try {
      for (Map.Entry<IdIndexEntry, Integer> entry : data.entrySet()) {
        Integer actual = reopened.get(entry.getKey());
        if (!entry.getValue().equals(actual)) {
          mismatches++;
        }
      }
    } finally {
      // closeAndDelete (not close + Files.deleteIfExists) -- a PersistentMapImpl is backed by
      // several sibling files (.len, .values, .values.at, _i, _i.len, ...), and this is the
      // library's own way to clean up the whole file family, not just the base file.
      reopened.closeAndDelete();
    }
    long readMillis = (System.nanoTime() - readStart) / 1_000_000;
    System.out.printf("Read %d entries in %d ms (%.0f entries/sec)%n",
        entryCount, readMillis, ratePerSec(entryCount, readMillis));
    System.out.println(mismatches == 0
        ? "Verification PASSED: all entries round-tripped through disk correctly."
        : "Verification FAILED: " + mismatches + " entries did not match after reopening.");

    System.out.println("Sample entries:");
    int shown = 0;
    for (Map.Entry<IdIndexEntry, Integer> entry : data.entrySet()) {
      if (shown++ >= 5) break;
      System.out.println("  " + entry.getKey() + " -> " + describeMask(entry.getValue()));
    }
  }

  private static int parseEntryCount(String[] args) {
    int defaultCount = 5_000_000;
    if (args.length == 0) {
      System.out.println("No entry count given, defaulting to " + defaultCount + " (usage: gradle run --args=\"<entryCount>\")");
      return defaultCount;
    }
    try {
      int n = Integer.parseInt(args[0]);
      if (n <= 0) {
        throw new NumberFormatException("must be positive");
      }
      return n;
    } catch (NumberFormatException e) {
      System.out.println("Invalid entry count '" + args[0] + "', defaulting to " + defaultCount);
      return defaultCount;
    }
  }

  /**
   * Derives keys from synthetic word hashes (like the real indexer does), retrying on collision
   * so the map ends up with exactly {@code entryCount} distinct entries, and assigns each a
   * realistic occurrence mask (mostly IN_CODE, occasionally combined with other contexts).
   */
  private static Map<IdIndexEntry, Integer> generateSyntheticIndex(int entryCount) {
    Random random = new Random(42);
    Map<IdIndexEntry, Integer> data = new LinkedHashMap<>(entryCount);
    while (data.size() < entryCount) {
      String word = randomWord(random);
      IdIndexEntry key = new IdIndexEntry(word.hashCode());
      data.putIfAbsent(key, randomOccurrenceMask(random));
    }
    return data;
  }

  private static String randomWord(Random random) {
    int length = 3 + random.nextInt(10);
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      char base = random.nextInt(5) == 0 ? 'A' : 'a';
      sb.append((char) (base + random.nextInt(26)));
    }
    return sb.toString();
  }

  private static int randomOccurrenceMask(Random random) {
    int mask = IN_CODE;
    if (random.nextInt(10) < 3) mask |= IN_COMMENTS;
    if (random.nextInt(10) < 2) mask |= IN_STRINGS;
    if (random.nextInt(50) == 0) mask |= IN_FOREIGN_LANGUAGES;
    if (random.nextInt(20) == 0) mask |= IN_PLAIN_TEXT;
    return mask;
  }

  private static String describeMask(int mask) {
    List<String> flags = new ArrayList<>();
    if ((mask & IN_CODE) != 0) flags.add("IN_CODE");
    if ((mask & IN_COMMENTS) != 0) flags.add("IN_COMMENTS");
    if ((mask & IN_STRINGS) != 0) flags.add("IN_STRINGS");
    if ((mask & IN_FOREIGN_LANGUAGES) != 0) flags.add("IN_FOREIGN_LANGUAGES");
    if ((mask & IN_PLAIN_TEXT) != 0) flags.add("IN_PLAIN_TEXT");
    return String.join("|", flags);
  }

  private static double ratePerSec(int count, long millis) {
    return millis == 0 ? count : count * 1000.0 / millis;
  }

  /** Mirrors {@code com.intellij.psi.impl.cache.impl.id.IdIndexEntry}: a boxed word hash. */
  private static final class IdIndexEntry {
    private final int wordHashCode;

    private IdIndexEntry(int wordHashCode) {
      this.wordHashCode = wordHashCode;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof IdIndexEntry && wordHashCode == ((IdIndexEntry) o).wordHashCode;
    }

    @Override
    public int hashCode() {
      return wordHashCode;
    }

    @Override
    public String toString() {
      return "IdIndexEntry(" + wordHashCode + ")";
    }
  }
}
