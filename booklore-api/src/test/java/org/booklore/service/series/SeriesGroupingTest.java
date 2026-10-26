package org.booklore.service.series;

import org.booklore.service.series.SeriesGrouping.Group;
import org.booklore.service.series.SeriesGrouping.Member;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesGroupingTest {

    private static Member book(long id, String series, Float number, String grId, String asin, String... authors) {
        return new Member(id, series, number, grId, asin, null, List.of(authors));
    }

    private static Member manual(long id, String series, String group, String... authors) {
        return new Member(id, series, null, null, null, group, List.of(authors));
    }

    @Test
    void sameNameDifferentAuthorsAreTwoSeries_namedByAuthor() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(8233, "Harem University", 1f, null, "B0D9DNS7D3", "Rex Sterling"),
                book(8231, "Harem University", 2f, null, "B0D9DNS7D3", "Rex Sterling"),
                book(8230, "Harem University", null, null, null, "Rex Sterling"),            // box set, no IDs
                book(8107, "Harem University", 1f, null, "B0D3KDJCS2", "Dirk Knight"),
                book(8109, "Harem University", null, null, null, "Dirk Knight")));            // box set, no IDs

        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(Group::displayName)
                .containsExactly("Harem University (Dirk Knight)", "Harem University (Rex Sterling)");
        assertThat(groups.get(0).bookIds()).containsExactly(8107L, 8109L);
        assertThat(groups.get(0).key()).isEqualTo("amzn:B0D3KDJCS2");
        assertThat(groups.get(1).bookIds()).containsExactly(8233L, 8231L, 8230L);
        assertThat(groups.get(1).key()).isEqualTo("amzn:B0D9DNS7D3");
    }

    @Test
    void sameNameDifferentAuthorsSplitEvenWithoutIds() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(1, "Harem University", 1f, null, null, "Rex Sterling"),
                book(2, "Harem University", 1f, null, null, "Dirk Knight")));

        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(Group::key).allMatch(k -> k.startsWith("name:harem university|")).doesNotHaveDuplicates();
    }

    @Test
    void guestAuthorsStayInTheSeries_throughAuthorsAndIds() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(8165, "Jack Reacher", 1f, null, null, "Lee Child"),
                book(1, "Jack Reacher", 25f, "40549", "B0775W2V36", "Andrew Child", "Lee Child"),
                book(2, "Jack Reacher", 26.5f, "40549", null, "Andrew Child"),               // solo short story
                book(3, "Jack Reacher", 29.1f, null, null, "Andrew Child")));                // no IDs yet

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().displayName()).isEqualTo("Jack Reacher");
        assertThat(groups.getFirst().key()).isEqualTo("gr:40549");
        assertThat(groups.getFirst().bookIds()).containsExactly(8165L, 1L, 2L, 3L);
    }

    @Test
    void fullHandoverWithSharedSeriesIdStaysOneSeries() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(1, "Jason Bourne", 1f, "40596", null, "Robert Ludlum"),
                book(2, "Jason Bourne", 4f, "40596", null, "Eric Van Lustbader")));

        assertThat(groups).hasSize(1);
    }

    @Test
    void oneAuthorsTwoSeriesWithDifferentIdsStayApart() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(1, "Hidden", 1f, null, "B000000001", "Same Author"),
                book(2, "Hidden", 1f, null, "B000000002", "Same Author")));

        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(Group::displayName).containsExactly("Hidden (Same Author)", "Hidden (Same Author) 2");
    }

    @Test
    void goodreadsOnlyAndAsinOnlyBooksOfOneAuthorJoin() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(1, "Alex Cross", 1f, "40543", null, "James Patterson"),
                book(2, "Alex Cross", 15f, null, "B07SV54YQQ", "James Patterson")));

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().key()).isEqualTo("gr:40543");
    }

    @Test
    void manualGroupOverridesTheRules() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(1, "Hidden", 1f, null, null, "Same Author"),
                book(2, "Hidden", 2f, null, null, "Same Author"),
                manual(3, "Hidden", "abc123", "Same Author")));

        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(Group::key).contains("manual:abc123");
        assertThat(groups.stream().filter(g -> g.key().equals("manual:abc123")).findFirst().orElseThrow().bookIds())
                .containsExactly(3L);
    }

    @Test
    void namesMatchIgnoringCaseAndSpacing() {
        List<Group> groups = SeriesGrouping.group(List.of(
                book(1, "Alex Cross", 1f, null, null, "James Patterson"),
                book(2, " alex  cross", 2f, null, null, "James Patterson")));

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().name()).isEqualTo("Alex Cross");
    }
}
