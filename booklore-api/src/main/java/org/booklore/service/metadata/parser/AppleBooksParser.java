package org.booklore.service.metadata.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Apple Books, through Apple's public iTunes Search API (no key). Looks books up by ISBN first, then
 * searches by title and author. Its covers are large: the artwork URL is asked for at
 * {@link #COVER_SIZE}px. The API allows about 20 requests a minute, so requests are paced through
 * {@link MetadataProviderGuard}. Gives the book's Apple ID (its store id, so
 * https://books.apple.com/book/id&lt;id&gt; opens it), rating out of 5 and number of ratings. Based on
 * Grimmory 0660e1d6.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AppleBooksParser implements BookParser {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultItem(String wrapperType, Long trackId, Long collectionId, String trackName, String collectionName,
                      String artistName, String description, List<String> genres, String primaryGenreName,
                      String releaseDate, String artworkUrl100, String trackViewUrl, String collectionViewUrl,
                      String language, Double averageUserRating, Integer userRatingCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultsWrapper(List<ResultItem> results) {
    }

    static final String DEFAULT_COUNTRY = "US";
    static final int COVER_SIZE = 1400;
    // The size is the last path segment's name, e.g. ".../100x100bb.jpg"; anchored so digits in the image hash can't match.
    private static final Pattern ARTWORK_SIZE_PATTERN = Pattern.compile("\\d+x\\d+(?:bb)?(?=\\.\\w+$)");
    private static final String ITUNES_SEARCH_URL = "https://itunes.apple.com/search";
    private static final String ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup";
    private static final int DEFAULT_LIMIT = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Set<String> VALID_WRAPPER_TYPES = Set.of("track", "collection", "audiobook");

    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final MetadataProviderGuard providerGuard;

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        List<BookMetadata> results = fetchMetadata(book, request);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        String entity = determineEntity(book);

        if (request.getIsbn() != null && !request.getIsbn().isBlank()) {
            String isbn = ParserUtils.cleanIsbn(request.getIsbn());
            List<BookMetadata> results = query(ITUNES_LOOKUP_URL, Map.of("isbn", isbn, "entity", entity));
            if (!results.isEmpty()) {
                return results;
            }
        }

        String title = request.getTitle() == null ? "" : request.getTitle().trim();
        String author = request.getAuthor() == null ? "" : request.getAuthor().trim();
        String term = title.isEmpty() ? author : (author.isEmpty() ? title : title + " " + author);
        if (term.isEmpty()) {
            return List.of();
        }
        return query(ITUNES_SEARCH_URL, Map.of("term", term, "entity", entity, "limit", String.valueOf(DEFAULT_LIMIT)));
    }

    private List<BookMetadata> query(String baseUrl, Map<String, String> params) {
        if (providerGuard.isBlocked(MetadataProvider.AppleBooks)) {
            log.debug("Apple Books is rate-limiting us; skipping");
            return List.of();
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl);
        params.forEach(builder::queryParam);
        URI uri = builder.queryParam("country", country()).encode().build().toUri();

        providerGuard.awaitTurn(MetadataProvider.AppleBooks);
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 403 || response.statusCode() == 429) {
                    log.warn("Apple Books returned {}; pausing requests to it", response.statusCode());
                    providerGuard.markBlocked(MetadataProvider.AppleBooks);
                    return List.of();
                }
                if (response.statusCode() >= 400) {
                    log.warn("Apple Books returned {} for {}", response.statusCode(), uri.getPath());
                    return List.of();
                }
                ResultsWrapper wrapper = objectMapper.readValue(body, ResultsWrapper.class);
                if (wrapper == null || wrapper.results() == null) {
                    return List.of();
                }
                return wrapper.results().stream()
                        .filter(r -> r.wrapperType() == null || VALID_WRAPPER_TYPES.contains(r.wrapperType()))
                        .map(AppleBooksParser::toMetadata)
                        .toList();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Apple Books request failed: {}", e.getMessage());
            return List.of();
        }
    }

    static BookMetadata toMetadata(ResultItem result) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        if (result.genres() != null) {
            categories.addAll(result.genres());
        } else if (result.primaryGenreName() != null) {
            categories.add(result.primaryGenreName());
        }
        return BookMetadata.builder()
                .provider(MetadataProvider.AppleBooks)
                .applebooksId(result.trackId() != null ? String.valueOf(result.trackId())
                        : result.collectionId() != null ? String.valueOf(result.collectionId()) : null)
                .applebooksRating(result.averageUserRating())
                .applebooksReviewCount(result.userRatingCount())
                .title(result.trackName() != null ? result.trackName() : result.collectionName())
                .authors(result.artistName() != null ? List.of(result.artistName()) : List.of())
                .description(result.description() != null ? Jsoup.parse(result.description()).text() : null)
                .publishedDate(toLocalDate(result.releaseDate()))
                .categories(categories)
                .thumbnailUrl(result.artworkUrl100() != null ? resizeArtworkUrl(result.artworkUrl100(), COVER_SIZE) : null)
                .externalUrl(result.trackViewUrl() != null ? result.trackViewUrl() : result.collectionViewUrl())
                .language(result.language() != null ? result.language().toLowerCase(Locale.ROOT) : null)
                .build();
    }

    static String resizeArtworkUrl(String url, int size) {
        var matcher = ARTWORK_SIZE_PATTERN.matcher(url);
        return matcher.find() ? matcher.replaceFirst(size + "x" + size + "bb") : url;
    }

    private static LocalDate toLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String determineEntity(Book book) {
        return book != null && book.getPrimaryFile() != null && book.getPrimaryFile().getBookType() == BookFileType.AUDIOBOOK
                ? "audiobook" : "ebook";
    }

    private String country() {
        MetadataProviderSettings settings = appSettingService.getAppSettings().getMetadataProviderSettings();
        String country = settings != null && settings.getAppleBooks() != null ? settings.getAppleBooks().getCountry() : null;
        return country == null || country.isBlank() ? DEFAULT_COUNTRY : country.trim().toUpperCase(Locale.ROOT);
    }
}
