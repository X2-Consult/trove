package org.booklore.service.opds;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.enums.IconType;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.repository.BookOpdsRepository;
import org.booklore.service.AuthorMetadataService;
import org.booklore.service.MagicShelfService;
import org.booklore.util.ArchiveUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpdsFeedService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuthenticationService authenticationService;
    private final OpdsBookService opdsBookService;
    private final MagicShelfService magicShelfService;
    private final MagicShelfBookService magicShelfBookService;
    private final AuthorMetadataService authorMetadataService;

    // Fixed navigation categories with no per-item data of their own, so each gets a
    // bundled default icon (see appendStaticImageLink) rather than a real cover/photo.
    private record RootNavEntry(String title, String urnId, String href, String kind, String description, String iconFile) {}

    private static final List<RootNavEntry> ROOT_NAV_ENTRIES = List.of(
            new RootNavEntry("All Books", "urn:booklore:catalog:all",
                    "/api/v1/opds/catalog?page=1&size=" + DEFAULT_PAGE_SIZE, "acquisition",
                    "Browse all available books", "all-books"),
            new RootNavEntry("Continue Reading", "urn:booklore:catalog:continue-reading",
                    "/api/v1/opds/continue-reading?page=1&size=" + DEFAULT_PAGE_SIZE, "acquisition",
                    "Books you're currently reading", "continue-reading"),
            new RootNavEntry("Recently Added", "urn:booklore:catalog:recent",
                    "/api/v1/opds/recent?page=1&size=" + DEFAULT_PAGE_SIZE, "acquisition",
                    "Recently added books", "recently-added"),
            new RootNavEntry("Libraries", "urn:booklore:navigation:libraries",
                    "/api/v1/opds/libraries", "navigation",
                    "Browse books by library", "libraries"),
            new RootNavEntry("Shelves", "urn:booklore:navigation:shelves",
                    "/api/v1/opds/shelves", "navigation",
                    "Browse your personal shelves", "shelves"),
            new RootNavEntry("Magic Shelves", "urn:booklore:navigation:magic-shelves",
                    "/api/v1/opds/magic-shelves", "navigation",
                    "Browse your smart, dynamic shelves", "magic-shelves"),
            new RootNavEntry("Authors", "urn:booklore:navigation:authors",
                    "/api/v1/opds/authors", "navigation",
                    "Browse books by author", "authors"),
            new RootNavEntry("Series", "urn:booklore:navigation:series",
                    "/api/v1/opds/series", "navigation",
                    "Browse books by series", "series"),
            new RootNavEntry("Surprise Me", "urn:booklore:catalog:surprise",
                    "/api/v1/opds/surprise", "acquisition",
                    "25 random books from the catalog", "surprise-me")
    );

    public String generateRootNavigation(HttpServletRequest request) {
        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:root</id>
                  <title>Booklore Catalog</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        for (RootNavEntry entry : ROOT_NAV_ENTRIES) {
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>%s</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=%s"/>
                        <content type="text">%s</content>
                    """.formatted(
                    escapeXml(entry.title()),
                    entry.urnId(),
                    now(),
                    escapeXml(entry.href()),
                    entry.kind(),
                    escapeXml(entry.description())
            ));
            appendStaticImageLink(feed, entry.iconFile());
            feed.append("  </entry>\n");
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateLibrariesNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        List<Library> libraries = opdsBookService.getAccessibleLibraries(userId);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:libraries</id>
                  <title>Libraries</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/libraries" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        for (Library library : libraries) {
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:library:%d</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">%s</content>
                    """.formatted(
                    escapeXml(library.getName()),
                    library.getId(),
                    now(),
                    escapeXml("/api/v1/opds/catalog?libraryId=" + library.getId()),
                    escapeXml(library.getName() != null ? library.getName() : "Library collection")
            ));
            appendIconImageLink(feed, library.getIcon(), library.getIconType());
            feed.append("  </entry>\n");
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateShelvesNavigation(HttpServletRequest request) {
        Long userId = getUserId();

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:shelves</id>
                  <title>Shelves</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/shelves" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        if (userId != null) {
            var shelves = opdsBookService.getUserShelves(userId);

            if (shelves != null) {
                for (var shelf : shelves) {
                    feed.append("""
                              <entry>
                                <title>%s</title>
                                <id>urn:booklore:shelf:%d</id>
                                <updated>%s</updated>
                                <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                                <content type="text">Personal shelf collection</content>
                            """.formatted(
                            escapeXml(shelf.getName()),
                            shelf.getId(),
                            now(),
                            escapeXml("/api/v1/opds/catalog?shelfId=" + shelf.getId())
                    ));
                    appendIconImageLink(feed, shelf.getIcon(), shelf.getIconType());
                    feed.append("  </entry>\n");
                }
            }
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateMagicShelvesNavigation(HttpServletRequest request) {
        Long userId = getUserId();

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:magic-shelves</id>
                  <title>Magic Shelves</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/magic-shelves" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        if (userId != null) {
            var magicShelves = magicShelfService.getUserShelvesForOpds(userId);

            if (magicShelves != null) {
                for (var shelf : magicShelves) {
                    feed.append("""
                              <entry>
                                <title>%s</title>
                                <id>urn:booklore:magic-shelf:%d</id>
                                <updated>%s</updated>
                                <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                                <content type="text">Smart, dynamic shelf collection</content>
                            """.formatted(
                            escapeXml(shelf.getName()),
                            shelf.getId(),
                            now(),
                            escapeXml("/api/v1/opds/catalog?magicShelfId=" + shelf.getId())
                    ));
                    appendIconImageLink(feed, shelf.getIcon(), shelf.getIconType());
                    feed.append("  </entry>\n");
                }
            }
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateAuthorsNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        List<AuthorEntity> authors = opdsBookService.getDistinctAuthors(userId);
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:navigation:authors</id>
                  <title>Authors</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), authors.size(), ((page - 1) * size) + 1, size, escapeXml(buildCurrentUrl(request, page, size))));

        appendPaginationLinks(feed, request, page, totalPages(authors.size(), size), size, "navigation");

        for (AuthorEntity author : pageOf(authors, page, size)) {
            String authorName = author.getName();
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:author:%s</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">Books by %s</content>
                    """.formatted(
                    escapeXml(authorName),
                    escapeXml(authorName),
                    now(),
                    escapeXml("/api/v1/opds/catalog?author=" + java.net.URLEncoder.encode(authorName, java.nio.charset.StandardCharsets.UTF_8)),
                    escapeXml(authorName)
            ));
            if (authorMetadataService.hasAuthorThumbnail(author.getId())) {
                String photoUrl = "/api/v1/opds/authors/" + author.getId() + "/photo";
                feed.append("    <link rel=\"http://opds-spec.org/image\" href=\"")
                        .append(escapeXml(photoUrl)).append("\" type=\"image/jpeg\"/>\n");
                feed.append("    <link rel=\"http://opds-spec.org/image/thumbnail\" href=\"")
                        .append(escapeXml(photoUrl)).append("\" type=\"image/jpeg\"/>\n");
            }
            feed.append("  </entry>\n");
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateSeriesNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        List<BookOpdsRepository.SeriesCoverProjection> seriesList = opdsBookService.getDistinctSeries(userId);
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:navigation:series</id>
                  <title>Series</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), seriesList.size(), ((page - 1) * size) + 1, size, escapeXml(buildCurrentUrl(request, page, size))));

        appendPaginationLinks(feed, request, page, totalPages(seriesList.size(), size), size, "navigation");

        for (BookOpdsRepository.SeriesCoverProjection series : pageOf(seriesList, page, size)) {
            String seriesName = series.getSeriesName();
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:series:%s</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">Books in the %s series</content>
                    """.formatted(
                    escapeXml(seriesName),
                    escapeXml(seriesName),
                    now(),
                    escapeXml("/api/v1/opds/catalog?series=" + java.net.URLEncoder.encode(seriesName, java.nio.charset.StandardCharsets.UTF_8)),
                    escapeXml(seriesName)
            ));
            if (series.getBookId() != null && series.getCoverUpdatedOn() != null) {
                String coverUrl = "/api/v1/opds/" + series.getBookId() + "/cover?" + series.getCoverUpdatedOn();
                feed.append("    <link rel=\"http://opds-spec.org/image\" href=\"")
                        .append(escapeXml(coverUrl)).append("\" type=\"image/jpeg\"/>\n");
                feed.append("    <link rel=\"http://opds-spec.org/image/thumbnail\" href=\"")
                        .append(escapeXml(coverUrl)).append("\" type=\"image/jpeg\"/>\n");
            }
            feed.append("  </entry>\n");
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateCatalogFeed(HttpServletRequest request) {
        Long libraryId = parseLongParam(request, "libraryId", null);
        Set<Long> shelfIds = parseShelfIds(request);
        Long magicShelfId = parseLongParam(request, "magicShelfId", null);
        String query = request.getParameter("q");
        String author = request.getParameter("author");
        String series = request.getParameter("series");
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        Long userId = getUserId();
        OpdsSortOrder sortOrder = getSortOrder();
        Page<Book> booksPage;

        if (magicShelfId != null) {
            booksPage = magicShelfBookService.getBooksByMagicShelfId(userId, magicShelfId, page - 1, size);
        } else if (author != null && !author.isBlank()) {
            booksPage = opdsBookService.getBooksByAuthorName(userId, author, page - 1, size);
        } else if (series != null && !series.isBlank()) {
            booksPage = opdsBookService.getBooksBySeriesName(userId, series, page - 1, size);
        } else {
            booksPage = opdsBookService.getBooksPage(userId, query, libraryId, shelfIds, page - 1, size);
        }

        // Apply user's preferred sort order
        booksPage = opdsBookService.applySortOrder(booksPage, sortOrder);

        String feedTitle = determineFeedTitle(libraryId, shelfIds, magicShelfId, author, series);
        String feedId = determineFeedId(libraryId, shelfIds, magicShelfId, author, series);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>%s</id>
                  <title>%s</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(
                feedId,
                escapeXml(feedTitle),
                now(),
                booksPage.getTotalElements(),
                ((page - 1) * size) + 1,
                size,
                escapeXml(buildCurrentUrl(request, page, size))
        ));

        appendPaginationLinks(feed, request, page, booksPage.getTotalPages(), size);

        booksPage.getContent().forEach(book -> appendBookEntry(feed, book));

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateRecentFeed(HttpServletRequest request) {
        Long userId = getUserId();
        OpdsSortOrder sortOrder = getSortOrder();
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        Page<Book> booksPage = opdsBookService.getRecentBooksPage(userId, page - 1, size);

        // Apply user's preferred sort order
        booksPage = opdsBookService.applySortOrder(booksPage, sortOrder);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:catalog:recent</id>
                  <title>Recently Added Books</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), booksPage.getTotalElements(), ((page - 1) * size) + 1, size, escapeXml(buildCurrentUrl(request, page, size))));

        appendPaginationLinks(feed, request, page, booksPage.getTotalPages(), size);

        booksPage.getContent().forEach(book -> appendBookEntry(feed, book));

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateContinueReadingFeed(HttpServletRequest request) {
        Long userId = getUserId();
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        Page<Book> booksPage = opdsBookService.getContinueReadingPage(userId, page - 1, size);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:catalog:continue-reading</id>
                  <title>Continue Reading</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), booksPage.getTotalElements(), ((page - 1) * size) + 1, size, escapeXml(buildCurrentUrl(request, page, size))));

        appendPaginationLinks(feed, request, page, booksPage.getTotalPages(), size);

        booksPage.getContent().forEach(book -> appendBookEntry(feed, book));

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateSurpriseFeed(HttpServletRequest request) {
        Long userId = getUserId();
        int count = 25;
        List<Book> books = opdsBookService.getRandomBooks(userId, count);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:catalog:surprise</id>
                  <title>Surprise Me</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>1</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="/api/v1/opds/surprise" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), books.size(), count));

        books.forEach(book -> appendBookEntry(feed, book));

        feed.append("</feed>");
        return feed.toString();
    }

    public String getOpenSearchDescription() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
                  <ShortName>Booklore</ShortName>
                  <Description>Search Booklore catalog</Description>
                  <Url type="application/atom+xml;profile=opds-catalog;kind=acquisition"
                       template="/api/v1/opds/catalog?q={searchTerms}"/>
                </OpenSearchDescription>
                """;
    }

    private void appendPaginationLinks(StringBuilder feed, HttpServletRequest request, int currentPage, int totalPages, int size) {
        appendPaginationLinks(feed, request, currentPage, totalPages, size, "acquisition");
    }

    /**
     * The Authors and Series lists come back whole (they carry the photo/cover each entry links to)
     * and are paged here, so OPDS apps get them a page at a time with first/previous/next/last links.
     */
    private static <T> List<T> pageOf(List<T> items, int page, int size) {
        int from = Math.min((page - 1) * size, items.size());
        return items.subList(from, Math.min(from + size, items.size()));
    }

    private static int totalPages(int totalItems, int size) {
        return (int) Math.ceil((double) totalItems / size);
    }

    private void appendPaginationLinks(StringBuilder feed, HttpServletRequest request, int currentPage, int totalPages, int size, String kind) {
        if (totalPages > 0) {
            feed.append("  <link rel=\"first\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, 1, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=" + kind + "\"/>\n");
        }
        if (currentPage > 1) {
            feed.append("  <link rel=\"previous\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, currentPage - 1, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=" + kind + "\"/>\n");
        }
        if (currentPage < totalPages) {
            feed.append("  <link rel=\"next\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, currentPage + 1, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=" + kind + "\"/>\n");
        }
        if (totalPages > 0) {
            feed.append("  <link rel=\"last\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, totalPages, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=" + kind + "\"/>\n");
        }
    }

    private String buildPaginationUrl(HttpServletRequest request, int page, int size) {
        String url = request.getRequestURI();
        StringBuilder result = new StringBuilder(url).append("?");

        String queryString = request.getQueryString();
        if (queryString != null) {
            Arrays.stream(queryString.split("&"))
                    .filter(param -> !param.startsWith("page=") && !param.startsWith("size="))
                    .forEach(param -> result.append(param).append("&"));
        }

        result.append("page=").append(page).append("&size=").append(size);

        return result.toString();
    }

    private String buildCurrentUrl(HttpServletRequest request, int page, int size) {
        return buildPaginationUrl(request, page, size);
    }

    private void appendBookEntry(StringBuilder feed, Book book) {
        feed.append("""
                  <entry>
                    <title>%s</title>
                    <id>urn:booklore:book:%d</id>
                    <updated>%s</updated>
                """.formatted(
                escapeXml(buildEntryTitle(book)),
                book.getId(),
                book.getAddedOn() != null ? book.getAddedOn() : now()
        ));

        if (book.getMetadata().getAuthors() != null) {
            book.getMetadata().getAuthors().forEach(author ->
                    feed.append("    <author><name>").append(escapeXml(author)).append("</name></author>\n")
            );
        }

        appendMetadata(feed, book);
        appendLinks(feed, book);

        feed.append("  </entry>\n");
    }

    /**
     * Builds the entry's display title as "Title: Subtitle" when a subtitle is present
     * (e.g. distinguishing per-volume subtitles under a shared series title), then appends
     * a compact progress glyph + percentage (e.g. "◑ 42%") when the requesting user has
     * unfinished progress on the book. Plain OPDS/Atom clients render all of this as
     * ordinary title text, so no extension support is required to see it - the same trick
     * Kavita's OPDS feed uses.
     */
    private String buildEntryTitle(Book book) {
        String title = book.getMetadata() != null && book.getMetadata().getTitle() != null
                ? book.getMetadata().getTitle() : "";

        String subtitle = book.getMetadata() != null ? book.getMetadata().getSubtitle() : null;
        if (subtitle != null && !subtitle.isBlank()) {
            title = title + ": " + subtitle;
        }

        Float percentage = resolveProgressPercent(book);
        if (percentage == null || percentage <= 0f) {
            return title;
        }

        return title + " " + progressGlyph(percentage) + " " + Math.round(percentage) + "%";
    }

    private Float resolveProgressPercent(Book book) {
        if (book.getEpubProgress() != null && book.getEpubProgress().getPercentage() != null) {
            return book.getEpubProgress().getPercentage();
        }
        if (book.getPdfProgress() != null && book.getPdfProgress().getPercentage() != null) {
            return book.getPdfProgress().getPercentage();
        }
        if (book.getCbxProgress() != null && book.getCbxProgress().getPercentage() != null) {
            return book.getCbxProgress().getPercentage();
        }
        if (book.getAudiobookProgress() != null && book.getAudiobookProgress().getPercentage() != null) {
            return book.getAudiobookProgress().getPercentage();
        }
        return null;
    }

    private String progressGlyph(float percentage) {
        if (percentage >= 99.5f) return "●"; // ● fully read
        if (percentage > 50f) return "◕";    // ◕
        if (percentage > 25f) return "◑";    // ◑
        return "◔";                          // ◔
    }

    private void appendMetadata(StringBuilder feed, Book book) {
        var meta = book.getMetadata();
        if (meta == null) return;

        if (meta.getPublisher() != null) {
            feed.append("    <dc:publisher>").append(escapeXml(meta.getPublisher())).append("</dc:publisher>\n");
        }
        if (meta.getLanguage() != null) {
            feed.append("    <dc:language>").append(escapeXml(meta.getLanguage())).append("</dc:language>\n");
        }
        if (meta.getCategories() != null) {
            meta.getCategories().forEach(cat ->
                    feed.append("    <category term=\"").append(escapeXml(cat)).append("\"/>\n")
            );
        }
        if (meta.getDescription() != null) {
            feed.append("    <summary>").append(escapeXml(meta.getDescription())).append("</summary>\n");
        }
        if (meta.getIsbn10() != null) {
            feed.append("    <dc:identifier>urn:isbn:").append(escapeXml(meta.getIsbn10())).append("</dc:identifier>\n");
        }
        if (meta.getAsin() != null) {
            feed.append("    <dc:identifier>urn:asin:").append(escapeXml(meta.getAsin())).append("</dc:identifier>\n");
        }
        // Series metadata
        if (meta.getSeriesName() != null) {
            feed.append("    <meta property=\"belongs-to-collection\" id=\"series\">")
                    .append(escapeXml(meta.getSeriesName())).append("</meta>\n");
            if (meta.getSeriesNumber() != null) {
                feed.append("    <meta property=\"group-position\" refines=\"#series\">")
                        .append(meta.getSeriesNumber()).append("</meta>\n");
            }
        }
    }

    // Bundled default icon for a fixed root-nav category (see ROOT_NAV_ENTRIES). These
    // ship as static files, served publicly by the app's normal static resource handler.
    private void appendStaticImageLink(StringBuilder feed, String iconFile) {
        String iconUrl = "/images/opds/" + iconFile + ".svg";
        feed.append("    <link rel=\"http://opds-spec.org/image\" href=\"")
                .append(escapeXml(iconUrl)).append("\" type=\"image/svg+xml\"/>\n");
        feed.append("    <link rel=\"http://opds-spec.org/image/thumbnail\" href=\"")
                .append(escapeXml(iconUrl)).append("\" type=\"image/svg+xml\"/>\n");
    }

    // Libraries, shelves, and magic shelves can each have a user-assigned icon. Only
    // CUSTOM_SVG carries an actual image (served via /api/v1/opds/icons/{name}); PRIME_NG
    // is just an icon-font class name, so there's nothing to link to for those.
    private void appendIconImageLink(StringBuilder feed, String icon, IconType iconType) {
        if (iconType != IconType.CUSTOM_SVG || icon == null || icon.isBlank()) {
            return;
        }
        String iconUrl = "/api/v1/opds/icons/" + java.net.URLEncoder.encode(icon, java.nio.charset.StandardCharsets.UTF_8);
        feed.append("    <link rel=\"http://opds-spec.org/image\" href=\"")
                .append(escapeXml(iconUrl)).append("\" type=\"image/svg+xml\"/>\n");
        feed.append("    <link rel=\"http://opds-spec.org/image/thumbnail\" href=\"")
                .append(escapeXml(iconUrl)).append("\" type=\"image/svg+xml\"/>\n");
    }

    private void appendLinks(StringBuilder feed, Book book) {
        // Add acquisition link for primary file
        if (book.getPrimaryFile() != null) {
            appendAcquisitionLink(feed, book.getId(), book.getPrimaryFile());
        }

        // Add acquisition links for alternative formats
        if (book.getAlternativeFormats() != null) {
            for (BookFile altFormat : book.getAlternativeFormats()) {
                appendAcquisitionLink(feed, book.getId(), altFormat);
            }
        }

        if (book.getMetadata() != null && book.getMetadata().getCoverUpdatedOn() != null) {
            String coverUrl = "/api/v1/opds/" + book.getId() + "/cover?" + book.getMetadata().getCoverUpdatedOn();
            feed.append("    <link rel=\"http://opds-spec.org/image\" href=\"")
                    .append(escapeXml(coverUrl)).append("\" type=\"image/jpeg\"/>\n");
            feed.append("    <link rel=\"http://opds-spec.org/image/thumbnail\" href=\"")
                    .append(escapeXml(coverUrl)).append("\" type=\"image/jpeg\"/>\n");
        }
    }

    private void appendAcquisitionLink(StringBuilder feed, Long bookId, BookFile bookFile) {
        if (bookFile == null || bookFile.getId() == null) return;

        String mimeType = fileMimeType(bookFile);
        feed.append("    <link href=\"/api/v1/opds/")
                .append(bookId)
                .append("/download?fileId=")
                .append(bookFile.getId())
                .append("\" rel=\"http://opds-spec.org/acquisition\" type=\"")
                .append(mimeType)
                .append("\"");

        // Add title attribute to help readers distinguish formats
        if (bookFile.getBookType() != null) {
            feed.append(" title=\"").append(bookFile.getBookType().name()).append("\"");
        }

        feed.append("/>\n");
    }

    private String determineFeedTitle(Long libraryId, Set<Long> shelfIds, Long magicShelfId, String author, String series) {
        if (magicShelfId != null) {
            return magicShelfBookService.getMagicShelfName(magicShelfId);
        }
        if (shelfIds != null && !shelfIds.isEmpty()) {
            if (shelfIds.size() == 1) {
                return opdsBookService.getShelfName(shelfIds.iterator().next());
            }
            return "Multiple Shelves";
        }
        if (libraryId != null) {
            return opdsBookService.getLibraryName(libraryId);
        }
        if (author != null && !author.isBlank()) {
            return "Books by " + author;
        }
        if (series != null && !series.isBlank()) {
            return series + " series";
        }
        return "Trove Catalog";
    }

    private String determineFeedId(Long libraryId, Set<Long> shelfIds, Long magicShelfId, String author, String series) {
        if (magicShelfId != null) {
            return "urn:booklore:magic-shelf:" + magicShelfId;
        }
        if (shelfIds != null && !shelfIds.isEmpty()) {
            if (shelfIds.size() == 1) {
                return "urn:booklore:shelf:" + shelfIds.iterator().next();
            }
            return "urn:booklore:shelves:" + String.join(",", shelfIds.stream().map(String::valueOf).sorted().toList());
        }
        if (libraryId != null) {
            return "urn:booklore:library:" + libraryId;
        }
        if (author != null && !author.isBlank()) {
            return "urn:booklore:author:" + author;
        }
        if (series != null && !series.isBlank()) {
            return "urn:booklore:series:" + series;
        }
        return "urn:booklore:catalog";
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now());
    }

    private boolean hasValidFilePath(BookFile bookFile) {
        return bookFile != null
                && bookFile.getFileName() != null
                && bookFile.getFilePath() != null;
    }

    private String fileMimeType(BookFile bookFile) {
        if (bookFile == null || bookFile.getBookType() == null) {
            return "application/octet-stream";
        }
        return switch (bookFile.getBookType()) {
            case PDF -> "application/pdf";
            case EPUB -> "application/epub+zip";
            case FB2 -> {
                if (hasValidFilePath(bookFile)) {
                    ArchiveUtils.ArchiveType type = ArchiveUtils.detectArchiveType(new File(bookFile.getFilePath()));
                    if (type == ArchiveUtils.ArchiveType.ZIP) {
                        yield "application/zip";
                    }
                }
                yield "application/x-fictionbook+xml";
            }
            case MOBI -> "application/x-mobipocket-ebook";
            case AZW3 -> "application/vnd.amazon.ebook";
            case CBX -> {
                if (bookFile.getArchiveType() != null) {
                    if (bookFile.getArchiveType() == ArchiveUtils.ArchiveType.RAR) {
                        yield "application/vnd.comicbook-rar";
                    }
                    if (bookFile.getArchiveType() == ArchiveUtils.ArchiveType.ZIP) {
                        yield "application/vnd.comicbook+zip";
                    }
                    if (bookFile.getArchiveType() == ArchiveUtils.ArchiveType.SEVEN_ZIP) {
                        yield "application/x-7z-compressed";
                    }
                }

                if (hasValidFilePath(bookFile)) {
                    ArchiveUtils.ArchiveType type = ArchiveUtils.detectArchiveType(new File(bookFile.getFilePath()));
                    // We only trust detection if it found something definite (not UNKNOWN)
                    if (type != ArchiveUtils.ArchiveType.UNKNOWN) {
                        yield switch (type) {
                            case RAR -> "application/vnd.comicbook-rar";
                            case ZIP -> "application/vnd.comicbook+zip";
                            case SEVEN_ZIP -> "application/x-7z-compressed";
                            default -> "application/vnd.comicbook+zip"; // Should not happen given the if check
                        };
                    }
                }

                String lower = bookFile.getFileName().toLowerCase();
                if (lower.endsWith(".cbr")) yield "application/vnd.comicbook-rar";
                if (lower.endsWith(".cbz")) yield "application/vnd.comicbook+zip";
                if (lower.endsWith(".cb7")) yield "application/x-7z-compressed";
                if (lower.endsWith(".cbt")) yield "application/x-tar";
                yield "application/vnd.comicbook+zip";
            }
            case AUDIOBOOK -> {
                String lower = bookFile.getFileName().toLowerCase();
                if (lower.endsWith(".mp3")) yield "audio/mpeg";
                if (lower.endsWith(".opus")) yield "audio/opus";
                yield "audio/mp4";
            }
        };
    }

    private String escapeXml(String input) {
        return input == null ? "" :
                input.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                        .replace("'", "&apos;");
    }

    private Long parseLongParam(HttpServletRequest request, String name, Long defaultValue) {
        try {
            String v = request.getParameter(name);
            if (v == null || v.isBlank()) return defaultValue;
            return Long.parseLong(v);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Set<Long> parseShelfIds(HttpServletRequest request) {
        String shelfIdParam = request.getParameter("shelfId");
        String shelfIdsParam = request.getParameter("shelfIds");

        Set<Long> shelfIds = new HashSet<>();

        // Support both single shelfId and comma-separated shelfIds
        if (shelfIdParam != null && !shelfIdParam.isBlank()) {
            try {
                shelfIds.add(Long.parseLong(shelfIdParam));
            } catch (NumberFormatException e) {
                log.warn("Invalid shelfId parameter: {}", shelfIdParam);
            }
        }

        if (shelfIdsParam != null && !shelfIdsParam.isBlank()) {
            for (String id : shelfIdsParam.split(",")) {
                try {
                    shelfIds.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Invalid shelf ID in shelfIds parameter: {}", id);
                }
            }
        }

        return shelfIds.isEmpty() ? null : shelfIds;
    }

    private Long getUserId() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        if (details == null || details.getOpdsUserV2() == null) {
            throw ApiError.FORBIDDEN.createException("OPDS authentication required");
        }
        return details.getOpdsUserV2().getUserId();
    }

    private OpdsSortOrder getSortOrder() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        return details != null && details.getOpdsUserV2() != null && details.getOpdsUserV2().getSortOrder() != null
                ? details.getOpdsUserV2().getSortOrder()
                : OpdsSortOrder.RECENT;
    }
}
