import com.intellij.util.indexing.ValueContainer;
import com.intellij.util.indexing.impl.UpdatableValueContainer;
import com.intellij.util.indexing.impl.ValueContainerExternalizer;
import com.intellij.util.indexing.impl.ValueContainerImpl;
import com.intellij.util.indexing.impl.ValueContainerInputRemapping;
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
import java.util.TreeMap;

/**
 * Mimics the real {@code com.intellij.psi.impl.cache.impl.id.IdIndex}: keys are word-hash-derived
 * {@link IdIndexEntry}s stored via {@link InlineKeyDescriptor}. Values are the same "postings"
 * shape the real {@code MapIndexStorage} uses -- a {@link ValueContainerImpl} mapping each
 * distinct occurrence bitmask (mirroring {@code com.intellij.psi.search.UsageSearchContext}, not
 * a real dependency here) to the (fake) file IDs that produced it.
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

  private static final DataExternalizer<Integer> MASK_EXTERNALIZER = new DataExternalizer<>() {
    @Override
    public void save(DataOutput out, Integer value) throws IOException {
      out.write(value & 0xFF);
    }

    @Override
    public Integer read(DataInput in) throws IOException {
      return in.readByte() & 0xFF;
    }
  };

  // Same wrapping MapIndexStorage.createValueContainerMap() does: the persistent map's real value
  // type is a postings list (value -> fileIds), not a bare Integer.
  private static final DataExternalizer<UpdatableValueContainer<Integer>> VALUE_EXTERNALIZER =
      new ValueContainerExternalizer<>(MASK_EXTERNALIZER, ValueContainerInputRemapping.IDENTITY);

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: gradle run --args=\"<entryCount> <directory>\" -- both arguments are required.");
      System.exit(1);
      return;
    }
    int entryCount = parseEntryCount(args);
    Path directory = parseDirectory(args);

    Map<IdIndexEntry, Map<Integer, Integer>> data = generateSyntheticIndex(entryCount);

    Path file = Files.createTempFile(directory, "id-index-demo", "");
    Files.deleteIfExists(file);
    System.out.println("Using base file: " + file.toAbsolutePath());

    // 1. populate and flush/close
    PersistentMapImpl<IdIndexEntry, UpdatableValueContainer<Integer>> map =
        new PersistentMapImpl<>(PersistentMapBuilder.newBuilder(file, KEY_DESCRIPTOR, VALUE_EXTERNALIZER));
    long writeStart = System.nanoTime();
    try {
      for (Map.Entry<IdIndexEntry, Map<Integer, Integer>> entry : data.entrySet()) {
        map.put(entry.getKey(), toValueContainer(entry.getValue()));
      }
      map.force();
    } finally {
      map.close();
    }
    long writeMillis = (System.nanoTime() - writeStart) / 1_000_000;
    System.out.printf("Wrote %d entries in %d ms (%.0f entries/sec)%n",
        entryCount, writeMillis, ratePerSec(entryCount, writeMillis));

    // 2. reopen from the SAME file path -- proves the round-trip through disk
    PersistentMapImpl<IdIndexEntry, UpdatableValueContainer<Integer>> reopened =
        new PersistentMapImpl<>(PersistentMapBuilder.newBuilder(file, KEY_DESCRIPTOR, VALUE_EXTERNALIZER));
    int mismatches = 0;
    long readStart = System.nanoTime();
    try {
      for (Map.Entry<IdIndexEntry, Map<Integer, Integer>> entry : data.entrySet()) {
        Map<Integer, Integer> actual = toFileIdToMaskMap(reopened.get(entry.getKey()));
        if (!entry.getValue().equals(actual)) {
          mismatches++;
        }
      }
    } finally {
      // A directory was explicitly requested -- most likely to inspect the resulting file family
      // (.len, .values, .values.at, _i, _i.len, ...) afterward, so leave it on disk (plain close,
      // not closeAndDelete).
      reopened.close();
    }
    long readMillis = (System.nanoTime() - readStart) / 1_000_000;
    System.out.printf("Read %d entries in %d ms (%.0f entries/sec)%n",
        entryCount, readMillis, ratePerSec(entryCount, readMillis));
    System.out.println(mismatches == 0
        ? "Verification PASSED: all entries round-tripped through disk correctly."
        : "Verification FAILED: " + mismatches + " entries did not match after reopening.");

    System.out.println("Sample entries:");
    int shown = 0;
    for (Map.Entry<IdIndexEntry, Map<Integer, Integer>> entry : data.entrySet()) {
      if (shown++ >= 5) break;
      System.out.println("  " + entry.getKey() + " -> " + describePostings(entry.getValue()));
    }
  }

  private static int parseEntryCount(String[] args) {
    int defaultCount = 5_000_000;
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
   * @return the directory to create the map's files in. Its files are kept on disk after the run
   *     (not deleted) so they can be inspected.
   */
  private static Path parseDirectory(String[] args) throws IOException {
    Path directory = Path.of(args[1]);
    Files.createDirectories(directory);
    return directory;
  }

  /**
   * Derives keys from synthetic word hashes (like the real indexer does), retrying on collision
   * so the map ends up with exactly {@code entryCount} distinct entries. Each entry's value is a
   * handful of FAKE file IDs (not real files -- just plausible-looking positive ints) mapped to a
   * realistic occurrence mask, mimicking the (value -> fileIds) postings shape of a real index.
   */
  private static Map<IdIndexEntry, Map<Integer, Integer>> generateSyntheticIndex(int entryCount) {
    Random random = new Random(42);
    int fakeFileCount = entryCount / 100;
    Map<IdIndexEntry, Map<Integer, Integer>> data = new LinkedHashMap<>(entryCount);
    while (data.size() < entryCount) {
      String word = randomWord(random);
      IdIndexEntry key = new IdIndexEntry(word.hashCode());
      if (data.containsKey(key)) {
        continue;
      }
      data.put(key, randomPostings(random, fakeFileCount));
    }
    return data;
  }

  /** fileId -> occurrence mask, for a handful of fake files "containing" this word. */
  private static Map<Integer, Integer> randomPostings(Random random, int fakeFileCount) {
    int postingsCount = 1 + random.nextInt(4);
    Map<Integer, Integer> postings = new TreeMap<>();
    while (postings.size() < postingsCount) {
      int fakeFileId = 1 + random.nextInt(fakeFileCount); // file IDs must be > 0
      postings.put(fakeFileId, randomOccurrenceMask(random));
    }
    return postings;
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

  private static UpdatableValueContainer<Integer> toValueContainer(Map<Integer, Integer> postings) {
    ValueContainerImpl<Integer> container = ValueContainerImpl.createNewValueContainer();
    for (Map.Entry<Integer, Integer> posting : postings.entrySet()) {
      container.addValue(posting.getKey(), posting.getValue());
    }
    return container;
  }

  private static Map<Integer, Integer> toFileIdToMaskMap(ValueContainer<Integer> container) {
    Map<Integer, Integer> result = new TreeMap<>();
    if (container != null) {
      container.forEach((fileId, mask) -> {
        result.put(fileId, mask);
        return true;
      });
    }
    return result;
  }

  private static String describePostings(Map<Integer, Integer> postings) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<Integer, Integer> posting : postings.entrySet()) {
      parts.add("file#" + posting.getKey() + "=" + describeMask(posting.getValue()));
    }
    return String.join(", ", parts);
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
