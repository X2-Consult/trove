package org.booklore.service.metadata.parser;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Loose "is this the same book?" check between the title we asked for and the title a provider
 * returned. Used to stop a search result for a sibling, a bundle or a different book in the same
 * series from lending its ASIN/ISBN to the book being matched.
 *
 * Two titles match when most of the words agree, every number in the wanted title appears in the
 * candidate ("Part Three" never matches "Part Two"), and either both or neither are collections
 * (bundles, omnibuses, box sets, "Books 1-5").
 */
public final class TitleMatcher {

    private static final double MIN_WORD_COVERAGE = 0.75;

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’`]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern COLLECTION = Pattern.compile(
            "\\b(bundle|omnibus|boxset|box set|boxed set|collection|anthology|compilation"
                    + "|complete (series|saga|set|books|trilogy|collection)"
                    + "|(books|parts|volumes|vols|novels|stories) \\d+ (to |through |and )?\\d+"
                    + "|\\d+ (book|novel|story) (set|series|collection|bundle))\\b");

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "of", "and", "or", "to", "in", "on", "for", "with", "by", "at", "from");

    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("one", "1"), Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"),
            Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"),
            Map.entry("nine", "9"), Map.entry("ten", "10"), Map.entry("eleven", "11"), Map.entry("twelve", "12"),
            Map.entry("thirteen", "13"), Map.entry("fourteen", "14"), Map.entry("fifteen", "15"),
            Map.entry("sixteen", "16"), Map.entry("seventeen", "17"), Map.entry("eighteen", "18"),
            Map.entry("nineteen", "19"), Map.entry("twenty", "20"),
            Map.entry("first", "1"), Map.entry("second", "2"), Map.entry("third", "3"), Map.entry("fourth", "4"),
            Map.entry("fifth", "5"), Map.entry("sixth", "6"), Map.entry("seventh", "7"), Map.entry("eighth", "8"),
            Map.entry("ninth", "9"), Map.entry("tenth", "10"),
            Map.entry("ii", "2"), Map.entry("iii", "3"), Map.entry("iv", "4"), Map.entry("vi", "6"),
            Map.entry("vii", "7"), Map.entry("viii", "8"), Map.entry("ix", "9"));

    private TitleMatcher() {
    }

    /** Joins a provider's title and subtitle, which is how Amazon and Goodreads split "Title: Part Two". */
    public static String fullTitle(String title, String subtitle) {
        if (title == null || title.isBlank()) return subtitle;
        if (subtitle == null || subtitle.isBlank()) return title;
        return title + ": " + subtitle;
    }

    /**
     * True when {@code candidate} looks like the same book as {@code wanted}. A blank title on
     * either side can't be judged and counts as a match, so callers keep their old behaviour.
     */
    public static boolean matches(String wanted, String candidate) {
        if (wanted == null || wanted.isBlank() || candidate == null || candidate.isBlank()) {
            return true;
        }
        String w = normalize(wanted);
        String c = normalize(candidate);
        if (w.isEmpty() || c.isEmpty()) return true;
        if (w.equals(c)) return true;

        if (isCollection(w) != isCollection(c)) return false;

        List<String> wTokens = tokens(w);
        List<String> cTokens = tokens(c);
        if (!numbers(cTokens).containsAll(numbers(wTokens))) return false;

        // The reverse direction covers a wanted title padded with author or file-name noise; a
        // one-word candidate is too vague to match that way.
        Set<String> cWords = words(cTokens);
        return coverage(words(wTokens), new HashSet<>(cTokens)) >= MIN_WORD_COVERAGE
                || (cWords.size() >= 2 && coverage(cWords, new HashSet<>(wTokens)) >= MIN_WORD_COVERAGE);
    }

    /** True when the title reads as a bundle, omnibus, box set or "Books 1-5" collection. */
    public static boolean isCollectionTitle(String title) {
        return title != null && isCollection(normalize(title));
    }

    private static boolean isCollection(String normalized) {
        return COLLECTION.matcher(normalized).find();
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = DIACRITICS.matcher(n).replaceAll("");
        n = n.toLowerCase(Locale.ROOT).replace("&", " and ");
        n = APOSTROPHES.matcher(n).replaceAll("");
        n = NON_ALNUM.matcher(n).replaceAll(" ").trim();
        if (n.isEmpty()) return n;
        StringJoiner joined = new StringJoiner(" ");
        for (String token : n.split(" ")) {
            joined.add(NUMBER_WORDS.getOrDefault(token, token));
        }
        return joined.toString();
    }

    private static List<String> tokens(String normalized) {
        return Arrays.asList(normalized.split(" "));
    }

    private static Set<String> numbers(List<String> tokens) {
        Set<String> nums = new HashSet<>();
        for (String t : tokens) {
            if (DIGITS.matcher(t).matches()) nums.add(t.replaceFirst("^0+(?=\\d)", ""));
        }
        return nums;
    }

    private static Set<String> words(List<String> tokens) {
        Set<String> words = new LinkedHashSet<>();
        for (String t : tokens) {
            if (!STOPWORDS.contains(t) && !DIGITS.matcher(t).matches()) words.add(t);
        }
        return words;
    }

    private static double coverage(Set<String> words, Set<String> pool) {
        if (words.isEmpty()) return 1.0;
        long hits = words.stream().filter(pool::contains).count();
        return (double) hits / words.size();
    }
}
