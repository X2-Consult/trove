package org.booklore.service.series;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.repository.BookMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The library's series as {@link SeriesGrouping} works them out, and the Split / Merge by hand that
 * overrides it for same-name series the series IDs and authors can't tell apart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeriesService {

    private static final int GROUP_ID_BYTES = 6;

    private final BookMetadataRepository bookMetadataRepository;

    /** The series of the books {@code user} can see. */
    @Transactional(readOnly = true)
    public List<SeriesGrouping.Group> getSeries(BookLoreUser user) {
        return SeriesGrouping.group(members(visibleLibraries(user)).values());
    }

    /** The series with this key among those {@code user} can see, or null. */
    @Transactional(readOnly = true)
    public SeriesGrouping.Group findByKey(BookLoreUser user, String key) {
        return getSeries(user).stream().filter(g -> g.key().equals(key)).findFirst().orElse(null);
    }

    /**
     * Makes these books one series of their own, apart from the other books with their series name.
     * Splitting off some of a series' books and merging two series are both this.
     *
     * @return the new series group
     */
    @Transactional
    public String groupTogether(BookLoreUser user, Collection<Long> bookIds) {
        Map<Long, SeriesGrouping.Member> members = checkedMembers(user, bookIds);
        Set<String> names = members.values().stream().map(m -> SeriesGrouping.normalize(m.seriesName())).collect(Collectors.toSet());
        if (names.size() != 1) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Only books with the same series name can be put in one series");
        }
        byte[] random = new byte[GROUP_ID_BYTES];
        new java.security.SecureRandom().nextBytes(random);
        String group = HexFormat.of().formatHex(random);
        bookMetadataRepository.updateSeriesGroup(members.keySet(), group);
        log.info("Put {} book(s) of series '{}' in series group {}", members.size(), names.iterator().next(), group);
        return group;
    }

    /** Lets these books' series be worked out from their IDs and authors again. */
    @Transactional
    public void ungroup(BookLoreUser user, Collection<Long> bookIds) {
        Map<Long, SeriesGrouping.Member> members = checkedMembers(user, bookIds);
        bookMetadataRepository.updateSeriesGroup(members.keySet(), null);
        log.info("Cleared the series group of {} book(s)", members.size());
    }

    private Map<Long, SeriesGrouping.Member> checkedMembers(BookLoreUser user, Collection<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No books given");
        }
        Map<Long, SeriesGrouping.Member> visible = members(visibleLibraries(user));
        Map<Long, SeriesGrouping.Member> chosen = new LinkedHashMap<>();
        for (Long id : bookIds) {
            SeriesGrouping.Member m = visible.get(id);
            if (m == null) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("Book " + id + " isn't in a series you can see");
            }
            chosen.put(id, m);
        }
        return chosen;
    }

    /** Null for everything (an admin), otherwise the user's libraries. */
    private static Set<Long> visibleLibraries(BookLoreUser user) {
        if (user == null || user.getPermissions() == null || user.getPermissions().isAdmin()) {
            return null;
        }
        return user.getAssignedLibraries() == null ? Set.of() : user.getAssignedLibraries().stream().map(Library::getId).collect(Collectors.toSet());
    }

    private Map<Long, SeriesGrouping.Member> members(Set<Long> libraryIds) {
        Map<Long, List<String>> authors = new HashMap<>();
        for (Object[] row : bookMetadataRepository.findSeriesAuthorRows()) {
            authors.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        Map<Long, SeriesGrouping.Member> members = new LinkedHashMap<>();
        for (Object[] row : bookMetadataRepository.findSeriesRows()) {
            Long bookId = (Long) row[0];
            Long libraryId = (Long) row[6];
            if (libraryIds != null && !libraryIds.contains(libraryId)) {
                continue;
            }
            members.put(bookId, new SeriesGrouping.Member(bookId, (String) row[1], (Float) row[2], (String) row[3], (String) row[4],
                    (String) row[5], authors.getOrDefault(bookId, List.of())));
        }
        return members;
    }
}
