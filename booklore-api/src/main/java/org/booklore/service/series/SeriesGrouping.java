package org.booklore.service.series;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Works out which books with the same series name are one series. Within a name:
 * <ol>
 *     <li>books put together by hand (a series group, from Split / Merge) are one series, as set;</li>
 *     <li>books sharing a Goodreads series ID or an Amazon series ASIN are one series, whoever wrote them;</li>
 *     <li>groups sharing an author join, unless both hold IDs from the same source and those differ -
 *         so Lee Child's and Andrew Child's Reacher books stay together, Rex Sterling's and Dirk Knight's
 *         "Harem University" stay apart, and one author's two differently-ID'd series aren't merged.</li>
 * </ol>
 * The UI applies the same rules, with the same keys (series-grouping.ts), so keep the two in step.
 */
public final class SeriesGrouping {

    private static final String NO_AUTHOR = "\u0000";

    private SeriesGrouping() {
    }

    /** One book with a series name. */
    public record Member(long bookId, String seriesName, Float seriesNumber, String seriesGoodreadsId, String seriesAsin,
                         String seriesGroup, List<String> authors) {
    }

    /**
     * One series. {@code key} stays the same while the series does: {@code gr:<id>}, {@code amzn:<asin>},
     * {@code manual:<series group>} for one put together by hand, or {@code name:<name>|<author>}. {@code displayName} adds the
     * lead author where two series share a name. Books are in series-number order.
     */
    public record Group(String key, String name, String displayName, List<Long> bookIds) {
    }

    public static List<Group> group(Collection<Member> members) {
        Map<String, List<Member>> byName = members.stream()
                .filter(m -> m.seriesName() != null && !m.seriesName().isBlank())
                .collect(Collectors.groupingBy(m -> normalize(m.seriesName()), TreeMap::new, Collectors.toList()));
        List<Group> groups = new ArrayList<>();
        byName.forEach((name, books) -> groups.addAll(groupName(name, books)));
        return groups;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final class Unit {
        final List<Member> members = new ArrayList<>();
        final Set<String> goodreadsIds = new TreeSet<>();
        final Set<String> asins = new TreeSet<>();
        final Set<String> authors = new HashSet<>();
        final String manualGroup;

        Unit(String manualGroup) {
            this.manualGroup = manualGroup;
        }

        void add(Member m) {
            members.add(m);
            if (hasText(m.seriesGoodreadsId())) goodreadsIds.add(m.seriesGoodreadsId().strip());
            if (hasText(m.seriesAsin())) asins.add(m.seriesAsin().strip().toUpperCase(Locale.ROOT));
            authors.addAll(authorKeys(m));
        }

        void absorb(Unit other) {
            other.members.forEach(this::add);
        }

        long minBookId() {
            return members.stream().mapToLong(Member::bookId).min().orElse(Long.MAX_VALUE);
        }

        boolean conflictsWith(Unit other) {
            return (!goodreadsIds.isEmpty() && !other.goodreadsIds.isEmpty() && Collections.disjoint(goodreadsIds, other.goodreadsIds))
                    || (!asins.isEmpty() && !other.asins.isEmpty() && Collections.disjoint(asins, other.asins));
        }

        boolean sharesAuthorWith(Unit other) {
            return !Collections.disjoint(authors, other.authors);
        }
    }

    private static List<Group> groupName(String normalizedName, List<Member> books) {
        List<Unit> fixed = new ArrayList<>();
        Map<String, Unit> manual = new TreeMap<>();
        List<Member> auto = new ArrayList<>();
        for (Member m : books) {
            if (hasText(m.seriesGroup())) {
                manual.computeIfAbsent(m.seriesGroup().strip(), Unit::new).add(m);
            } else {
                auto.add(m);
            }
        }
        fixed.addAll(manual.values());

        List<Unit> units = mergeByAuthor(unitsByIds(auto));
        List<Unit> all = new ArrayList<>(units);
        all.addAll(fixed);
        all.sort(Comparator.comparingLong(Unit::minBookId));

        List<Group> groups = new ArrayList<>();
        Map<String, Integer> displayCounts = new HashMap<>();
        for (Unit unit : all) {
            List<Member> sorted = unit.members.stream()
                    .sorted(Comparator.comparing((Member m) -> m.seriesNumber() == null ? Float.MAX_VALUE : m.seriesNumber())
                            .thenComparingLong(Member::bookId))
                    .toList();
            String name = sorted.getFirst().seriesName().strip();
            String display = all.size() > 1 ? name + " (" + leadAuthor(sorted) + ")" : name;
            int seen = displayCounts.merge(display, 1, Integer::sum);
            if (seen > 1) {
                display = display + " " + seen;
            }
            groups.add(new Group(key(unit, normalizedName), name, display, sorted.stream().map(Member::bookId).toList()));
        }
        return groups;
    }

    /** Books joined through shared series IDs: a book with both IDs links the books holding either. */
    private static List<Unit> unitsByIds(List<Member> books) {
        int n = books.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        Map<String, Integer> firstWithId = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Member m = books.get(i);
            for (String id : List.of(
                    hasText(m.seriesGoodreadsId()) ? "gr:" + m.seriesGoodreadsId().strip() : "",
                    hasText(m.seriesAsin()) ? "amzn:" + m.seriesAsin().strip().toUpperCase(Locale.ROOT) : "")) {
                if (id.isEmpty()) continue;
                Integer other = firstWithId.putIfAbsent(id, i);
                if (other != null) union(parent, i, other);
            }
        }
        Map<Integer, Unit> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), r -> new Unit(null)).add(books.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    /** Joins groups through shared authors, biggest first, never across conflicting IDs. */
    private static List<Unit> mergeByAuthor(List<Unit> units) {
        List<Unit> pending = new ArrayList<>(units);
        pending.sort(Comparator.comparingInt((Unit u) -> -u.members.size()).thenComparingLong(Unit::minBookId));
        List<Unit> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            Unit current = pending.removeFirst();
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Iterator<Unit> it = pending.iterator(); it.hasNext(); ) {
                    Unit other = it.next();
                    if (current.sharesAuthorWith(other) && !current.conflictsWith(other)) {
                        current.absorb(other);
                        it.remove();
                        grew = true;
                    }
                }
            }
            result.add(current);
        }
        return result;
    }

    private static String key(Unit unit, String normalizedName) {
        if (unit.manualGroup != null) return "manual:" + unit.manualGroup;
        if (!unit.goodreadsIds.isEmpty()) return "gr:" + unit.goodreadsIds.iterator().next();
        if (!unit.asins.isEmpty()) return "amzn:" + unit.asins.iterator().next();
        String author = unit.authors.stream().sorted().findFirst().orElse(NO_AUTHOR);
        return "name:" + normalizedName + "|" + (author.equals(NO_AUTHOR) ? "" : author);
    }

    /** The author on most of the series' books, alphabetically first on a tie. */
    private static String leadAuthor(List<Member> books) {
        Map<String, Long> counts = books.stream()
                .flatMap(m -> m.authors() == null ? java.util.stream.Stream.<String>empty() : m.authors().stream())
                .filter(SeriesGrouping::hasText)
                .map(String::strip)
                .collect(Collectors.groupingBy(a -> a, Collectors.counting()));
        return counts.entrySet().stream()
                .min(Comparator.comparing((Map.Entry<String, Long> e) -> -e.getValue()).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("Unknown author");
    }

    private static Set<String> authorKeys(Member m) {
        Set<String> keys = m.authors() == null ? new HashSet<>() : m.authors().stream()
                .filter(SeriesGrouping::hasText)
                .map(SeriesGrouping::normalize)
                .collect(Collectors.toCollection(HashSet::new));
        if (keys.isEmpty()) keys.add(NO_AUTHOR);
        return keys;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

}
