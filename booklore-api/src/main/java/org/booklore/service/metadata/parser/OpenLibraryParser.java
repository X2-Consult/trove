package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.response.OpenLibraryApiResponse;
import org.booklore.model.dto.response.OpenLibraryWorkResponse;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.model.enums.MetadataProvider;
import tools.jackson.databind.JsonNode;
import org.booklore.util.BookUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OpenLibraryParser implements BookParser {

    private static final String BASE_URL = "https://openlibrary.org";
    private static final String COVERS_BASE_URL = "https://covers.openlibrary.org";
    private static final String SEARCH_FIELDS = "key,title,author_name,first_publish_year,isbn,cover_i,publisher,language,number_of_pages_median,subject,id_amazon";
    // Open Library's id_amazon field mixes genuine ASINs in with plain ISBN-10s Amazon also
    // catalogs by; only the "B0..." shape is a real, non-ISBN Amazon identifier.
    private static final Pattern ASIN_PATTERN = Pattern.compile("^B0[0-9A-Z]{8}$");
    private static final int SEARCH_LIMIT = 10;
    private static final int SUBJECT_LIMIT = 10;
    private static final Pattern WORK_PREFIX = Pattern.compile("^/works/");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    // "number_of_pages" is edition-level and absent from search/work responses; "pagination"
    // is free text like "xvi, 348 p." - pull the last run of digits from it as a fallback.
    private static final Pattern PAGINATION_DIGITS_PATTERN = Pattern.compile("(\\d+)(?!.*\\d)");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OpenLibraryParser(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newHttpClient());
    }

    public OpenLibraryParser(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        URI uri = buildSearchUri(book, fetchMetadataRequest);
        if (uri == null) {
            return List.of();
        }

        try {
            log.info("Open Library API URL: {}", uri);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("Open Library API request failed. Status: {}, Response: {}", response.statusCode(), response.body());
                return List.of();
            }

            OpenLibraryApiResponse searchResponse = objectMapper.readValue(response.body(), OpenLibraryApiResponse.class);
            if (searchResponse == null || searchResponse.getDocs() == null) {
                return List.of();
            }

            return searchResponse.getDocs().stream()
                    .map(this::mapDoc)
                    .filter(this::isUsableResult)
                    .toList();
        } catch (IOException e) {
            log.error("IO error while fetching metadata from Open Library API: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            log.error("Request to Open Library API was interrupted");
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        List<BookMetadata> results = fetchMetadata(book, fetchMetadataRequest);
        if (results.isEmpty()) {
            return null;
        }

        BookMetadata top = results.getFirst();
        String workId = extractWorkId(top.getExternalUrl());
        if (workId == null) {
            return top;
        }

        BookMetadata workMetadata = fetchWorkMetadata(workId);
        BookMetadata result = workMetadata == null ? top : merge(top, workMetadata);

        if (result.getPageCount() == null || result.getPageCount() <= 0) {
            Integer editionPageCount = fetchEditionPageCount(firstNonBlank(
                    ParserUtils.cleanIsbn(fetchMetadataRequest.getIsbn()),
                    firstNonBlank(result.getIsbn13(), result.getIsbn10())));
            if (editionPageCount != null) {
                result = result.toBuilder().pageCount(editionPageCount).build();
            }
        }

        return result;
    }

    private Integer fetchEditionPageCount(String isbn) {
        if (isbn == null || isbn.isBlank()) {
            return null;
        }
        URI uri = URI.create(BASE_URL + "/isbn/" + isbn + ".json");
        try {
            log.info("Open Library edition API URL: {}", uri);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                log.debug("Open Library edition request failed. Status: {}", response.statusCode());
                return null;
            }

            var node = objectMapper.readTree(response.body());
            if (node == null) {
                return null;
            }
            if (node.has("number_of_pages") && node.get("number_of_pages").isNumber()) {
                int pages = node.get("number_of_pages").asInt();
                if (pages > 0) {
                    return pages;
                }
            }
            if (node.has("pagination")) {
                var matcher = PAGINATION_DIGITS_PATTERN.matcher(node.get("pagination").asString(""));
                if (matcher.find()) {
                    int pages = Integer.parseInt(matcher.group(1));
                    if (pages > 0) {
                        return pages;
                    }
                }
            }
            return null;
        } catch (IOException e) {
            log.debug("IO error while fetching Open Library edition metadata: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            log.debug("Open Library edition request was interrupted");
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            log.debug("Failed to parse Open Library edition page count: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Looks an author up by name via Open Library's authors API and returns their bio, photo,
     * and cross-provider ids ({@code remote_ids.amazon} -> ASIN, {@code remote_ids.goodreads}).
     * Free, no auth, no bot wall - the primary source for author enrichment. Returns null if
     * no author matches or the API is unreachable.
     */
    public AuthorSearchResult fetchAuthor(String authorName) {
        if (authorName == null || authorName.isBlank()) {
            return null;
        }
        try {
            String authorKey = searchAuthorKey(authorName);
            if (authorKey == null) {
                return null;
            }
            return fetchAuthorRecord(authorKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Open Library author lookup failed for '{}': {}", authorName, e.getMessage());
            return null;
        }
    }

    private String searchAuthorKey(String name) throws IOException, InterruptedException {
        List<String> keys = searchAuthorKeys(name, 5);
        return keys.isEmpty() ? null : keys.getFirst();
    }

    /**
     * Open Library author keys for a name, best first: exact name matches ahead of the rest, then
     * by how many works each author has (a name shared by several people is usually the prolific one).
     */
    public List<String> searchAuthorKeys(String name, int limit) throws IOException, InterruptedException {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL + "/search/authors.json")
                .queryParam("q", name)
                .queryParam("limit", Math.max(limit, 5))
                .build()
                .encode()
                .toUri();
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return List.of();
        }
        JsonNode docs = objectMapper.readTree(response.body()).path("docs");
        if (!docs.isArray() || docs.isEmpty()) {
            return List.of();
        }
        String target = normalizeName(name);
        record Candidate(String key, boolean exact, long works) {}
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode doc : docs) {
            String key = trimToNull(doc.path("key").asString(""));
            if (key != null) {
                candidates.add(new Candidate(key, normalizeName(doc.path("name").asString("")).equals(target), doc.path("work_count").asLong(0)));
            }
        }
        return candidates.stream()
                .sorted(java.util.Comparator.comparing((Candidate c) -> !c.exact()).thenComparing(c -> -c.works()))
                .map(Candidate::key)
                .limit(limit)
                .toList();
    }

    /** An author's Open Library record by key (such as {@code OL23919A}), or null. */
    public AuthorSearchResult fetchAuthorByKey(String authorKey) {
        if (authorKey == null || !authorKey.matches("OL\\d+A")) {
            return null;
        }
        try {
            return fetchAuthorRecord(authorKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Open Library author record {} failed: {}", authorKey, e.getMessage());
            return null;
        }
    }

    private AuthorSearchResult fetchAuthorRecord(String authorKey) throws IOException, InterruptedException {
        URI uri = URI.create(BASE_URL + "/authors/" + authorKey + ".json");
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode node = objectMapper.readTree(response.body());
        if (node == null) {
            return null;
        }
        JsonNode remoteIds = node.path("remote_ids");
        return AuthorSearchResult.builder()
                .source(AuthorMetadataSource.OPEN_LIBRARY)
                .name(trimToNull(node.path("name").asString("")))
                .description(extractAuthorBio(node.get("bio")))
                .imageUrl(extractAuthorPhoto(node.path("photos")))
                .asin(validAsin(trimToNull(remoteIds.path("amazon").asString(""))))
                .goodreadsId(trimToNull(remoteIds.path("goodreads").asString("")))
                .openlibraryId(authorKey)
                .build();
    }

    private String extractAuthorBio(JsonNode bio) {
        if (bio == null || bio.isMissingNode() || bio.isNull()) {
            return null;
        }
        String raw = bio.isObject() ? bio.path("value").asString("") : bio.asString("");
        if (raw.isBlank()) {
            return null;
        }
        String cleaned = WHITESPACE_PATTERN.matcher(Jsoup.parse(raw).text().trim()).replaceAll(" ");
        return cleaned.isBlank() ? null : cleaned;
    }

    private String extractAuthorPhoto(JsonNode photos) {
        if (photos == null || !photos.isArray()) {
            return null;
        }
        for (JsonNode photo : photos) {
            long id = photo.isIntegralNumber() ? photo.longValue() : -1L;
            if (id > 0) {
                return COVERS_BASE_URL + "/a/id/" + id + "-L.jpg";
            }
        }
        return null;
    }

    private String validAsin(String value) {
        return value != null && ASIN_PATTERN.matcher(value).matches() ? value : null;
    }

    private String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        return WHITESPACE_PATTERN.matcher(s.toLowerCase().replaceAll("[^\\p{Alnum}]+", " ")).replaceAll(" ").trim();
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private URI buildSearchUri(Book book, FetchMetadataRequest request) {
        String isbn = ParserUtils.cleanIsbn(request.getIsbn());
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE_URL + "/search.json")
                .queryParam("limit", SEARCH_LIMIT)
                .queryParam("fields", SEARCH_FIELDS);

        if (isbn != null && !isbn.isBlank()) {
            return builder.queryParam("isbn", isbn).build().toUri();
        }

        String title = Optional.ofNullable(request.getTitle())
                .filter(value -> !value.isBlank())
                .orElseGet(() -> Optional.ofNullable(book.getPrimaryFile())
                        .map(primaryFile -> primaryFile.getFileName())
                        .filter(fileName -> !fileName.isBlank())
                        .map(BookUtils::cleanFileName)
                        .orElse(null));

        if (title == null || title.isBlank()) {
            return null;
        }

        builder.queryParam("title", title);
        if (request.getAuthor() != null && !request.getAuthor().isBlank()) {
            builder.queryParam("author", request.getAuthor());
        }

        return builder.build().toUri();
    }

    private BookMetadata fetchWorkMetadata(String workId) {
        URI uri = URI.create(BASE_URL + "/works/" + workId + ".json");

        try {
            log.info("Open Library work API URL: {}", uri);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("Open Library work request failed. Status: {}, Response: {}", response.statusCode(), response.body());
                return null;
            }

            OpenLibraryWorkResponse work = objectMapper.readValue(response.body(), OpenLibraryWorkResponse.class);
            return work == null ? null : mapWork(work);
        } catch (IOException e) {
            log.error("IO error while fetching Open Library work metadata: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            log.error("Open Library work request was interrupted");
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private BookMetadata mapDoc(OpenLibraryApiResponse.Doc doc) {
        String workId = stripWorkPrefix(doc.getKey());
        Map<String, String> isbns = extractIsbns(doc.getIsbn());

        return BookMetadata.builder()
                .provider(MetadataProvider.OpenLibrary)
                .title(cleanString(doc.getTitle()))
                .authors(cleanList(doc.getAuthorName()))
                .publishedDate(parseYear(doc.getFirstPublishYear()))
                .isbn10(isbns.get("ISBN_10"))
                .isbn13(isbns.get("ISBN_13"))
                .publisher(first(doc.getPublisher()))
                .language(cleanString(first(doc.getLanguage())))
                .pageCount(doc.getNumberOfPagesMedian())
                .categories(cleanSubjects(doc.getSubject()))
                .thumbnailUrl(coverUrl(doc.getCoverI()))
                .externalUrl(workId == null ? null : BASE_URL + "/works/" + workId)
                .asin(extractAsin(doc.getIdAmazon()))
                .build();
    }

    private String extractAsin(List<String> idAmazon) {
        if (idAmazon == null) {
            return null;
        }
        return idAmazon.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> ASIN_PATTERN.matcher(id).matches())
                .findFirst()
                .orElse(null);
    }

    private BookMetadata mapWork(OpenLibraryWorkResponse work) {
        String workId = stripWorkPrefix(work.getKey());
        Integer coverId = work.getCovers() == null || work.getCovers().isEmpty() ? null : work.getCovers().getFirst();

        return BookMetadata.builder()
                .provider(MetadataProvider.OpenLibrary)
                .title(cleanString(work.getTitle()))
                .description(extractDescription(work.getDescription()))
                .publishedDate(parseDate(work.getFirstPublishDate()))
                .categories(cleanSubjects(work.getSubjects()))
                .thumbnailUrl(coverUrl(coverId))
                .externalUrl(workId == null ? null : BASE_URL + "/works/" + workId)
                .build();
    }

    private BookMetadata merge(BookMetadata searchMetadata, BookMetadata workMetadata) {
        return searchMetadata.toBuilder()
                .title(firstNonBlank(workMetadata.getTitle(), searchMetadata.getTitle()))
                .description(firstNonBlank(workMetadata.getDescription(), searchMetadata.getDescription()))
                .publishedDate(workMetadata.getPublishedDate() != null ? workMetadata.getPublishedDate() : searchMetadata.getPublishedDate())
                .categories(firstNonEmpty(workMetadata.getCategories(), searchMetadata.getCategories()))
                .thumbnailUrl(firstNonBlank(workMetadata.getThumbnailUrl(), searchMetadata.getThumbnailUrl()))
                .externalUrl(firstNonBlank(workMetadata.getExternalUrl(), searchMetadata.getExternalUrl()))
                .build();
    }

    private boolean isUsableResult(BookMetadata metadata) {
        return metadata.getTitle() != null && !metadata.getTitle().isBlank();
    }

    private Map<String, String> extractIsbns(List<String> rawIsbns) {
        if (rawIsbns == null || rawIsbns.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        for (String rawIsbn : rawIsbns) {
            String isbn = ParserUtils.cleanIsbn(rawIsbn);
            if (isbn == null) {
                continue;
            }
            if (!result.containsKey("ISBN_10") && isbn.length() == 10) {
                result.put("ISBN_10", isbn);
            } else if (!result.containsKey("ISBN_13") && isbn.length() == 13) {
                result.put("ISBN_13", isbn);
            }
            if (result.containsKey("ISBN_10") && result.containsKey("ISBN_13")) {
                break;
            }
        }
        return result;
    }

    private String extractDescription(Object rawDescription) {
        if (rawDescription == null) {
            return null;
        }
        String description;
        if (rawDescription instanceof String value) {
            description = value;
        } else if (rawDescription instanceof Map<?, ?> valueMap && valueMap.get("value") instanceof String value) {
            description = value;
        } else {
            return null;
        }

        String cleaned = Jsoup.parse(description).text();
        cleaned = WHITESPACE_PATTERN.matcher(cleaned.trim()).replaceAll(" ");
        return cleaned.isBlank() ? null : cleaned;
    }

    private Set<String> cleanSubjects(List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return Set.of();
        }
        return subjects.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(subject -> !subject.isBlank())
                .limit(SUBJECT_LIMIT)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private LocalDate parseYear(Integer year) {
        if (year == null || year < 1000 || year > 9999) {
            return null;
        }
        return LocalDate.of(year, 1, 1);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank() || date.length() < 4) {
            return null;
        }
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            return parseYear(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String coverUrl(Integer coverId) {
        return coverId == null ? null : COVERS_BASE_URL + "/b/id/" + coverId + "-L.jpg";
    }

    private String stripWorkPrefix(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return WORK_PREFIX.matcher(key).replaceFirst("");
    }

    private String extractWorkId(String externalUrl) {
        if (externalUrl == null || externalUrl.isBlank()) {
            return null;
        }
        int index = externalUrl.lastIndexOf("/works/");
        if (index < 0) {
            return null;
        }
        String id = externalUrl.substring(index + "/works/".length());
        return id.isBlank() ? null : id;
    }

    private String cleanString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return WHITESPACE_PATTERN.matcher(value.trim()).replaceAll(" ");
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : cleanString(values.getFirst());
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private <T extends Collection<String>> T firstNonEmpty(T first, T second) {
        return first != null && !first.isEmpty() ? first : second;
    }
}
