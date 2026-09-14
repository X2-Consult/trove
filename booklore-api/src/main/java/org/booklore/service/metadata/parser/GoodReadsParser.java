package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookReview;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.BookUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.FuzzyScore;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.configurationprocessor.json.JSONArray;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@AllArgsConstructor
public class GoodReadsParser implements BookParser, DetailedMetadataProvider {

    private static final String BASE_SEARCH_URL = "https://www.goodreads.com/search?q=";
    private static final String BASE_AUTOCOMPLETE_URL = "https://www.goodreads.com/book/auto_complete?format=json&q=";
    private static final String BASE_BOOK_URL = "https://www.goodreads.com/book/show/";
    private static final String BASE_AUTHOR_URL_PREFIX = "https://www.goodreads.com/author/";
    private static final String BASE_ISBN_URL = "https://www.goodreads.com/book/isbn/";
    private static final int COUNT_DETAILED_METADATA_TO_GET = 3;
    private static final int COUNT_DETAILED_METADATA_TO_GET_RETRY = 2;
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern BOOK_SHOW_ID_PATTERN = Pattern.compile("/book/show/(\\d+)");
    private static final Pattern SERIES_SUFFIX_PATTERN = Pattern.compile("\\s*\\([^,(]+,\\s*#[\\d.]+\\)\\s*$");
    private static final Pattern SERIES_FROM_TITLE_PATTERN = Pattern.compile("\\(([^,(]+),\\s*#([\\d.]+)\\)\\s*$");
    private static final Pattern COVER_SIZE_TOKEN_PATTERN = Pattern.compile("\\._S[XY]\\d+_\\.");
    private static final Pattern GOODREADS_AUTHOR_ID_PATTERN = Pattern.compile("/author/show/(\\d+)");
    private static final Pattern KCR_PREVIEW_ASIN_PATTERN = Pattern.compile("[?&]asin=([A-Z0-9]{10})(?:&|$)");

    private final AppSettingService appSettingService;
    private final MetadataProviderGuard providerGuard;
    private final BrowserPageFetcher browserPageFetcher;

    private record TitleInfo(String title, String subtitle) {}

    // Carries a book ID plus the autocomplete preview — used so the search loop
    // can fall back to the autocomplete data when the detail page is WAF-gated.
    private record SearchTarget(String id, BookMetadata autocompletePreview) {
        SearchTarget(String id) { this(id, null); }
    }

    private static final class WafChallengeException extends RuntimeException {
        WafChallengeException() { super("WAF challenge detected"); }
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        String existingGoodreadsId = getExistingGoodreadsId(book);
        if (existingGoodreadsId != null) {
            log.info("GoodReads: Using existing Goodreads ID: {}", existingGoodreadsId);
            try {
                Document document = fetchDoc(BASE_BOOK_URL + existingGoodreadsId);
                BookMetadata metadata = parseBookDetails(document, existingGoodreadsId);
                if (metadata != null) {
                    return metadata;
                }
                log.warn("GoodReads: Failed to parse details for existing ID: {}, falling back to search", existingGoodreadsId);
            } catch (WafChallengeException e) {
                log.warn("GoodReads: WAF challenge for existing ID: {}, falling back to search", existingGoodreadsId);
            } catch (Exception e) {
                log.warn("GoodReads: Error fetching existing ID {}: {}, falling back to search", existingGoodreadsId, e.getMessage());
            }
        }

        BookMetadata byIsbn = fetchByIsbn(fetchMetadataRequest.getIsbn());
        if (byIsbn != null) {
            return byIsbn;
        }

        List<SearchTarget> targets = searchTargets(book, fetchMetadataRequest);
        List<BookMetadata> fetchedMetadata = fetchMetadataFromTargets(targets.stream().limit(1).toList());
        return fetchedMetadata.isEmpty() ? null : fetchedMetadata.getFirst();
    }

    private String getExistingGoodreadsId(Book book) {
        if (book == null || book.getMetadata() == null) {
            return null;
        }
        String goodreadsId = book.getMetadata().getGoodreadsId();
        if (goodreadsId == null || goodreadsId.isBlank()) {
            return null;
        }
        String numericId = goodreadsId.split("-")[0].split("\\.")[0];
        try {
            Long.parseLong(numericId);
            return goodreadsId;
        } catch (NumberFormatException e) {
            log.debug("GoodReads: Invalid Goodreads ID format: {}", goodreadsId);
            return null;
        }
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest fetchMetadataRequest) {
        BookMetadata byIsbn = fetchByIsbn(fetchMetadataRequest.getIsbn());
        if (byIsbn != null) {
            return List.of(byIsbn);
        }

        List<SearchTarget> targets = searchTargets(book, fetchMetadataRequest).stream()
                .limit(COUNT_DETAILED_METADATA_TO_GET)
                .toList();
        List<BookMetadata> results = fetchMetadataFromTargets(targets);

        if (results.isEmpty()
                && fetchMetadataRequest.getTitle() != null && !fetchMetadataRequest.getTitle().isBlank()
                && fetchMetadataRequest.getAuthor() != null && !fetchMetadataRequest.getAuthor().isBlank()) {
            log.info("GoodReads: No results with title+author, retrying with title only.");
            FetchMetadataRequest titleOnlyRequest = FetchMetadataRequest.builder()
                    .bookId(fetchMetadataRequest.getBookId())
                    .providers(fetchMetadataRequest.getProviders())
                    .isbn(fetchMetadataRequest.getIsbn())
                    .title(fetchMetadataRequest.getTitle())
                    .asin(fetchMetadataRequest.getAsin())
                    .build();
            targets = searchTargets(book, titleOnlyRequest).stream()
                    .limit(COUNT_DETAILED_METADATA_TO_GET_RETRY)
                    .toList();
            results = fetchMetadataFromTargets(targets);
        }

        return results;
    }

    // GoodReads redirects /book/isbn/{isbn} to the matching book page, which is a more exact
    // match than a title search. Returns null when there's no ISBN or the lookup doesn't land.
    private BookMetadata fetchByIsbn(String rawIsbn) {
        String isbn = ParserUtils.cleanIsbn(rawIsbn);
        if (isbn == null || isbn.isBlank()) {
            return null;
        }
        log.info("Goodreads Query URL (ISBN): {}{}", BASE_ISBN_URL, isbn);
        try {
            Document doc = fetchDoc(BASE_ISBN_URL + isbn);
            String ogUrl = Optional.ofNullable(doc.selectFirst("meta[property=og:url]"))
                    .map(e -> e.attr("content"))
                    .orElse(null);

            if (ogUrl != null && !ogUrl.isBlank()) {
                String goodreadsId = ogUrl.substring(ogUrl.lastIndexOf('/') + 1);
                if (!goodreadsId.isBlank()) {
                    return parseBookDetails(doc, goodreadsId);
                }
            }
        } catch (WafChallengeException e) {
            log.warn("GoodReads: WAF challenge on ISBN lookup, falling back to search");
        } catch (Exception e) {
            log.warn("GoodReads: ISBN lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private List<BookMetadata> fetchMetadataFromTargets(List<SearchTarget> targets) {
        List<BookMetadata> results = new ArrayList<>();
        boolean detailReachable = true;

        for (SearchTarget target : targets) {
            BookMetadata candidate = null;

            if (detailReachable) {
                log.info("GoodReads: Fetching metadata for ID: {}", target.id());
                try {
                    Document document = fetchDoc(BASE_BOOK_URL + target.id());
                    candidate = parseBookDetails(document, target.id());
                } catch (WafChallengeException e) {
                    log.warn("GoodReads: WAF challenge on detail page for ID: {}", target.id());
                    if (target.autocompletePreview() != null) detailReachable = false;
                } catch (Exception e) {
                    log.error("Error fetching metadata for book: {}", target.id(), e);
                }
            }

            if (candidate == null && target.autocompletePreview() != null) {
                log.info("GoodReads: Using autocomplete fallback for ID: {}", target.id());
                candidate = target.autocompletePreview();
            }

            if (candidate != null) results.add(candidate);
        }

        return results;
    }

    BookMetadata parseBookDetails(Document document, String goodreadsId) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder()
                .goodreadsId(goodreadsId)
                .provider(MetadataProvider.GoodReads);

        try {
            JSONObject apolloStateJson = getJson(document)
                    .getJSONObject("props")
                    .getJSONObject("pageProps")
                    .getJSONObject("apolloState");

            LinkedHashSet<String> keySet = getJsonKeys(apolloStateJson);

            extractContributorDetails(apolloStateJson, keySet, builder);
            extractBookDetails(apolloStateJson, keySet, builder);
            extractWorkDetails(apolloStateJson, keySet, builder);

            appSettingService.getAppSettings()
                    .getMetadataPublicReviewsSettings()
                    .getProviders()
                    .stream()
                    .filter(cfg -> cfg.getProvider() == MetadataProvider.GoodReads && cfg.isEnabled())
                    .findFirst()
                    .ifPresent(cfg -> extractReviews(apolloStateJson, keySet, builder, cfg.getMaxReviews()));

        } catch (Exception e) {
            log.error("Error parsing book details for providerBookId: {}", goodreadsId, e);
            return null;
        }

        return builder.build();
    }

    private void extractContributorDetails(JSONObject apolloStateJson, LinkedHashSet<String> keySet, BookMetadata.BookMetadataBuilder builder) {
        String contributorKey = findKeyByPrefix(keySet, "Contributor:kca");
        String contributorName = getJsonStringField(apolloStateJson, contributorKey, "name");
        if (contributorName != null) {
            builder.authors(List.of(contributorName));
        }
    }

    private void extractReviews(JSONObject apolloStateJson, LinkedHashSet<String> keySet, BookMetadata.BookMetadataBuilder builder, int maxReviews) {
        List<String> allReviewKeys = findKeysByPrefixAll(keySet, "Review:kca");
        List<BookReview> reviews = new ArrayList<>();

        int count = 0;
        int index = 0;

        while (count < maxReviews && index < allReviewKeys.size()) {
            String reviewKey = allReviewKeys.get(index);
            index++;
            try {
                JSONObject reviewJson = apolloStateJson.getJSONObject(reviewKey);
                String creatorRef = reviewJson.getJSONObject("creator").getString("__ref");
                JSONObject userJson = apolloStateJson.optJSONObject(creatorRef);

                String reviewerName = null;
                Integer followersCount = null;
                Integer textReviewsCount = null;
                if (userJson != null) {
                    reviewerName = userJson.optString("name", null);
                    followersCount = userJson.has("followersCount") ? userJson.optInt("followersCount") : null;
                    textReviewsCount = userJson.has("textReviewsCount") ? userJson.optInt("textReviewsCount") : null;
                }

                String rawBody = reviewJson.optString("text", null);
                String plainBody = rawBody != null ? Jsoup.parse(rawBody).text() : null;

                if (plainBody == null || plainBody.trim().isEmpty()) {
                    continue;
                }

                BookReview review = BookReview.builder()
                        .metadataProvider(MetadataProvider.GoodReads)
                        .date(parseEpochMillis(String.valueOf(reviewJson.getLong("updatedAt"))))
                        .body(plainBody.trim())
                        .rating(Float.valueOf(reviewJson.optString("rating", null)))
                        .spoiler(reviewJson.optBoolean("spoilerStatus", false))
                        .reviewerName(reviewerName != null ? reviewerName.trim() : null)
                        .followersCount(followersCount)
                        .textReviewsCount(textReviewsCount)
                        .build();
                reviews.add(review);
                count++;
            } catch (Exception e) {
                log.error("Error fetching review: {}, Error: {}", reviewKey, e.getMessage());
            }
        }

        builder.bookReviews(reviews);
    }

    private Instant parseEpochMillis(String millisString) {
        try {
            long millis = Long.parseLong(millisString);
            return Instant.ofEpochMilli(millis);
        } catch (NumberFormatException e) {
            log.warn("Invalid epoch millis: {}", millisString, e);
            return null;
        }
    }

    private List<String> findKeysByPrefixAll(LinkedHashSet<String> keySet, String prefix) {
        List<String> matchingKeys = new ArrayList<>();
        for (String key : keySet) {
            if (key.startsWith(prefix)) {
                matchingKeys.add(key);
            }
        }
        return matchingKeys;
    }

    private void extractBookDetails(JSONObject apolloStateJson, LinkedHashSet<String> keySet, BookMetadata.BookMetadataBuilder builder) {
        JSONObject bookJson = getValidBookJson(apolloStateJson, keySet);
        if (bookJson == null) {
            return;
        }

        TitleInfo titleInfo = parseTitleInfo(bookJson.optString("title"));
        builder.title(titleInfo.title())
                .subtitle(titleInfo.subtitle())
                // GoodReads stores this as either "description" or "description({\"stripped\":true})"
                .description(firstNonBlankJsonField(bookJson, "description"))
                .thumbnailUrl(normalizeNull(bookJson.optString("imageUrl")))
                .categories(extractGenres(bookJson));

        JSONObject detailsJson = bookJson.optJSONObject("details");
        if (detailsJson != null) {
            builder.pageCount(parseNumber(detailsJson.optString("numPages"), Integer::parseInt))
                    .publishedDate(convertToLocalDate(detailsJson.optString("publicationTime")))
                    .publisher(normalizeNull(detailsJson.optString("publisher")))
                    .isbn10(normalizeNull(detailsJson.optString("isbn")))
                    .isbn13(normalizeNull(detailsJson.optString("isbn13")))
                    .asin(normalizeNull(detailsJson.optString("asin")));

            JSONObject languageJson = detailsJson.optJSONObject("language");
            if (languageJson != null) {
                builder.language(normalizeNull(languageJson.optString("name")));
            }
        }

        // The book's primary series is its first bookSeries entry. Take the name from the Series node
        // that entry points at rather than from whichever Series node comes first in apolloState:
        // books in several series (Stormlight Archive #1 / Cosmere #6) and other books embedded on the
        // page carry more Series nodes, which could pair this book's number with another series' name
        // or give a standalone book a series.
        JSONArray bookSeriesJson = bookJson.optJSONArray("bookSeries");
        JSONObject primarySeries = bookSeriesJson != null ? bookSeriesJson.optJSONObject(0) : null;
        if (primarySeries != null) {
            builder.seriesNumber(parseNumber(primarySeries.optString("userPosition"), Float::parseFloat));
            JSONObject seriesRef = primarySeries.optJSONObject("series");
            String ref = seriesRef != null ? normalizeNull(seriesRef.optString("__ref")) : null;
            JSONObject seriesNode = ref != null ? apolloStateJson.optJSONObject(ref) : null;
            if (seriesNode != null) {
                builder.seriesName(normalizeNull(seriesNode.optString("title")));
            }
        }
    }

    private void extractWorkDetails(JSONObject apolloStateJson, LinkedHashSet<String> keySet, BookMetadata.BookMetadataBuilder builder) {
        String workKey = findKeyByPrefix(keySet, "Work:kca:");
        if (workKey == null) {
            return;
        }
        JSONObject workJson = apolloStateJson.optJSONObject(workKey);
        if (workJson == null) {
            return;
        }
        JSONObject statsJson = workJson.optJSONObject("stats");
        if (statsJson != null) {
            builder.goodreadsRating(parseNumber(statsJson.optString("averageRating"), Double::parseDouble))
                    .goodreadsReviewCount(parseNumber(statsJson.optString("ratingsCount"), Integer::parseInt));
        }
    }

    private TitleInfo parseTitleInfo(String fullTitle) {
        if (fullTitle == null || "null".equals(fullTitle)) {
            return new TitleInfo(null, null);
        }
        String[] parts = fullTitle.split(":", 2);
        String title = parts[0].trim();
        String subtitle = parts.length > 1 ? parts[1].trim() : null;
        return new TitleInfo(title.isEmpty() ? null : title, subtitle);
    }

    private <T extends Number> T parseNumber(String value, Function<String, T> parser) {
        if (value == null || value.isEmpty() || "null".equals(value)) {
            return null;
        }
        try {
            return parser.apply(value);
        } catch (NumberFormatException e) {
            log.warn("Error parsing number: {}", value);
            return null;
        }
    }

    private String normalizeNull(String s) {
        return "null".equals(s) || (s != null && s.isEmpty()) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private LinkedHashSet<String> getJsonKeys(JSONObject apolloStateJson) {
        LinkedHashSet<String> keySet = new LinkedHashSet<>();
        Iterator<String> keys = apolloStateJson.keys();
        while (keys.hasNext()) {
            keySet.add(keys.next());
        }
        return keySet;
    }

    /**
     * A book page's apolloState can hold several {@code Book:kca:} nodes (the page's book plus
     * stubs for "readers also enjoyed", series siblings, etc). The old "first node with a title"
     * rule sometimes returned a stub, dropping the description, ASIN, ISBN, page count and
     * publisher. Score the candidates and keep the richest: only the page's own book node has a
     * {@code details} object, and we prefer the fullest description.
     */
    private JSONObject getValidBookJson(JSONObject apolloStateJson, LinkedHashSet<String> keySet) {
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String key : keySet) {
            if (!key.contains("Book:kca:")) {
                continue;
            }
            JSONObject bookJson = apolloStateJson.optJSONObject(key);
            if (bookJson == null) {
                continue;
            }
            String title = bookJson.optString("title");
            if (title == null || title.isEmpty()) {
                continue;
            }

            int score = 0;
            if (bookJson.optJSONObject("details") != null) {
                score += 1000;
            }
            String description = firstNonBlankJsonField(bookJson, "description");
            if (description != null) {
                score += Math.min(description.length(), 500);
            }
            if (bookJson.has("bookGenres")) {
                score += 10;
            }

            if (score > bestScore) {
                bestScore = score;
                best = bookJson;
            }
        }
        return best;
    }

    private String findKeyByPrefix(LinkedHashSet<String> keySet, String prefix) {
        return keySet.stream()
                .filter(key -> key.contains(prefix))
                .findFirst()
                .orElse(null);
    }

    private String getJsonStringField(JSONObject apolloStateJson, String key, String fieldName) {
        if (key == null) {
            return null;
        }
        try {
            return apolloStateJson.getJSONObject(key).getString(fieldName);
        } catch (Exception e) {
            log.warn("Error fetching {} from {}: {}", fieldName, key, e.getMessage());
            return null;
        }
    }

    private Set<String> extractGenres(JSONObject bookJson) {
        try {
            Set<String> genres = new HashSet<>();
            JSONArray bookGenresJsonArray = bookJson.getJSONArray("bookGenres");
            for (int i = 0; i < bookGenresJsonArray.length(); i++) {
                JSONObject genreJson = bookGenresJsonArray.getJSONObject(i).getJSONObject("genre");
                genres.add(genreJson.getString("name"));
            }
            return genres;
        } catch (Exception e) {
            log.error("Error extracting genres from book: {}, Error: {}", bookJson, e.getMessage());
        }
        return null;
    }

    private LocalDate convertToLocalDate(String timestamp) {
        if (timestamp == null || timestamp.isBlank() || "null".equals(timestamp)) {
            return null;
        }
        try {
            long millis = Long.parseLong(timestamp);
            return Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        } catch (Exception e) {
            log.error("Invalid publication time: {}, Error: {}", timestamp, e.getMessage());
            return null;
        }
    }

    public JSONObject getJson(Element document) {
        try {
            Element scriptElement = document.getElementById("__NEXT_DATA__");

            if (scriptElement != null) {
                String jsonString = scriptElement.html();
                return new JSONObject(jsonString);
            } else {
                log.warn("No JSON script element found!");
            }
        } catch (Exception e) {
            log.error("No JSON script element found!", e);
        }
        return null;
    }

    public String generateSearchUrl(String searchTerm) {
        String encodedSearchTerm = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
        String url = BASE_SEARCH_URL + encodedSearchTerm;
        log.info("Goodreads Query URL: {}", url);
        return url;
    }

    public List<BookMetadata> fetchMetadataPreviews(Book book, FetchMetadataRequest request) {
        return searchTargets(book, request).stream()
                .map(t -> t.autocompletePreview() != null ? t.autocompletePreview() :
                        BookMetadata.builder()
                                .goodreadsId(t.id())
                                .provider(MetadataProvider.GoodReads)
                                .build())
                .toList();
    }

    private List<SearchTarget> searchTargets(Book book, FetchMetadataRequest request) {
        String searchTerm = getSearchTerm(book, request);
        if (searchTerm == null || searchTerm.isEmpty()) {
            log.info("GoodReads: No metadata previews found (no ISBN, title, or filename).");
            return Collections.emptyList();
        }

        // Try the autocomplete JSON endpoint first — not gated behind WAF
        List<SearchTarget> autocompleteTargets = fetchAutocompleteTargets(searchTerm, request);
        String title = request.getTitle();
        if (autocompleteTargets.isEmpty() && title != null && !title.isBlank() && !searchTerm.equals(title)) {
            // Autocomplete matches on title text, so with the author appended it can return only
            // "Summary of <title>" knockoffs, which the author filter then rejects. Retry on the
            // bare title; the author filter still applies.
            autocompleteTargets = fetchAutocompleteTargets(title, request);
        }
        if (!autocompleteTargets.isEmpty()) {
            return autocompleteTargets;
        }

        // Fall back to HTML search page
        try {
            String searchUrl = BASE_SEARCH_URL + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
            log.info("GoodReads: Search URL: {}", searchUrl);
            Document doc = fetchDoc(searchUrl);
            Element tableList = doc.select("table.tableList").first();

            if (tableList == null) {
                log.warn("GoodReads: No results table found for search term: {}", searchTerm);
                return Collections.emptyList();
            }

            Elements previewBooks = tableList.select("tr[itemtype=http://schema.org/Book]");
            FuzzyScore fuzzyScore = new FuzzyScore(Locale.ENGLISH);
            String queryAuthor = request.getAuthor();
            List<SearchTarget> targets = new ArrayList<>();

            for (Element previewBook : previewBooks) {
                List<String> authors = extractAuthorsPreview(previewBook);

                if (queryAuthor != null && !queryAuthor.isBlank()) {
                    List<String> queryAuthorTokens = List.of(WHITESPACE_PATTERN.split(queryAuthor.toLowerCase()));
                    boolean matches = authors.stream()
                            .flatMap(a -> Arrays.stream(WHITESPACE_PATTERN.split(a.toLowerCase())))
                            .anyMatch(actual -> {
                                for (String query : queryAuthorTokens) {
                                    int score = fuzzyScore.fuzzyScore(actual, query);
                                    int maxScore = Math.max(fuzzyScore.fuzzyScore(query, query),
                                            fuzzyScore.fuzzyScore(actual, actual));
                                    double similarity = maxScore > 0 ? (double) score / maxScore : 0;
                                    if (similarity >= 0.5) return true;
                                }
                                return false;
                            });

                    if (!matches) continue;
                }

                Integer id = extractGoodReadsIdPreview(previewBook);
                if (id == null) continue;

                BookMetadata preview = BookMetadata.builder()
                        .goodreadsId(String.valueOf(id))
                        .title(extractTitlePreview(previewBook))
                        .authors(authors)
                        .provider(MetadataProvider.GoodReads)
                        .thumbnailUrl(extractThumbnailPreview(previewBook))
                        .build();
                targets.add(new SearchTarget(String.valueOf(id), preview));
            }

            return targets;

        } catch (WafChallengeException e) {
            log.warn("GoodReads: WAF challenge on search page for term: {}", searchTerm);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Error fetching search page: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchTarget> fetchAutocompleteTargets(String searchTerm, FetchMetadataRequest request) {
        try {
            String autocompleteUrl = BASE_AUTOCOMPLETE_URL + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
            log.info("GoodReads: Autocomplete URL: {}", autocompleteUrl);
            String jsonBody = fetchJsonBody(autocompleteUrl);
            return jsonBody != null ? parseAutocompleteTargets(jsonBody, request) : Collections.emptyList();
        } catch (Exception e) {
            log.warn("GoodReads: Autocomplete fetch failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchTarget> parseAutocompleteTargets(String jsonBody, FetchMetadataRequest request) {
        try {
            JSONArray items = new JSONArray(jsonBody);
            Set<String> seen = new LinkedHashSet<>();
            FuzzyScore fuzzyScore = new FuzzyScore(Locale.ENGLISH);
            String queryAuthor = request.getAuthor();
            List<SearchTarget> targets = new ArrayList<>();

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = getAutocompleteBookId(item);
                if (id == null || seen.contains(id)) continue;
                seen.add(id);

                String author = getAutocompleteAuthor(item);
                if (queryAuthor != null && !queryAuthor.isBlank() && author != null) {
                    List<String> queryTokens = List.of(WHITESPACE_PATTERN.split(queryAuthor.toLowerCase()));
                    List<String> authorTokens = List.of(WHITESPACE_PATTERN.split(author.toLowerCase()));
                    boolean matches = authorTokens.stream().anyMatch(actual -> {
                        for (String query : queryTokens) {
                            int score = fuzzyScore.fuzzyScore(actual, query);
                            int maxScore = Math.max(fuzzyScore.fuzzyScore(query, query),
                                    fuzzyScore.fuzzyScore(actual, actual));
                            if (maxScore > 0 && (double) score / maxScore >= 0.5) return true;
                        }
                        return false;
                    });
                    if (!matches) continue;
                }

                BookMetadata preview = mapAutocompleteItem(item, id);
                if (preview != null) targets.add(new SearchTarget(id, preview));
            }

            return targets;
        } catch (Exception e) {
            log.warn("GoodReads: Failed to parse autocomplete response: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    BookMetadata mapAutocompleteItem(JSONObject item, String id) {
        try {
            String rawTitle = normalizeNull(item.optString("bookTitleBare"));
            if (rawTitle == null) {
                String fullTitle = normalizeNull(item.optString("title"));
                rawTitle = fullTitle != null ? SERIES_SUFFIX_PATTERN.matcher(fullTitle).replaceFirst("").trim() : null;
            }
            if (rawTitle == null) return null;

            TitleInfo titleInfo = parseTitleInfo(rawTitle);
            String author = getAutocompleteAuthor(item);

            String seriesName = null;
            Float seriesNumber = null;
            String fullTitle = item.optString("title");
            if (fullTitle != null) {
                Matcher m = SERIES_FROM_TITLE_PATTERN.matcher(fullTitle);
                if (m.find()) {
                    seriesName = normalizeNull(m.group(1));
                    seriesNumber = parseNumber(m.group(2), Float::parseFloat);
                }
            }

            String coverUrl = normalizeNull(item.optString("imageUrl"));
            if (coverUrl != null) {
                coverUrl = COVER_SIZE_TOKEN_PATTERN.matcher(coverUrl).replaceFirst(".");
            }

            String description = extractAutocompleteDescription(item);

            Double rating = parseNumber(normalizeNull(item.optString("avgRating")), Double::parseDouble);
            Integer ratingCount = parseNumber(normalizeNull(item.optString("ratingsCount")), v -> Integer.parseInt(v.replace(",", "")));

            return BookMetadata.builder()
                    .goodreadsId(id)
                    .provider(MetadataProvider.GoodReads)
                    .title(titleInfo.title())
                    .subtitle(titleInfo.subtitle())
                    .authors(author != null ? List.of(author) : null)
                    .asin(extractAutocompleteAsin(item))
                    .description(description)
                    .pageCount(parseNumber(normalizeNull(item.optString("numPages")), Integer::parseInt))
                    .thumbnailUrl(coverUrl)
                    .seriesName(seriesName)
                    .seriesNumber(seriesNumber)
                    .goodreadsRating(rating)
                    .goodreadsReviewCount(ratingCount)
                    .build();
        } catch (Exception e) {
            log.warn("GoodReads: Failed to map autocomplete item: {}", e.getMessage());
            return null;
        }
    }

    // The autocomplete item has no ASIN field, but for books with a Kindle edition its Kindle
    // Cloud Reader preview link carries one: https://read.amazon.com.au/kp/embed?asin=B08FFJS3YW&...
    private String extractAutocompleteAsin(JSONObject item) {
        String previewUrl = normalizeNull(item.optString("kcrPreviewUrl"));
        if (previewUrl == null) return null;
        Matcher matcher = KCR_PREVIEW_ASIN_PATTERN.matcher(previewUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    // Autocomplete descriptions are cut at ~200 characters and flagged "truncated". A stub like
    // that would win the description slot over a full one from a lower-priority provider, and in
    // REPLACE_MISSING mode it would never be replaced later, so leave the field empty instead.
    private String extractAutocompleteDescription(JSONObject item) {
        try {
            Object desc = item.opt("description");
            if (desc == null) return null;
            String html;
            if (desc instanceof JSONObject descObj) {
                if (descObj.optBoolean("truncated", false)) return null;
                html = normalizeNull(descObj.optString("html"));
            } else {
                html = normalizeNull(desc.toString());
            }
            if (html == null) return null;
            return Jsoup.parse(html).text();
        } catch (Exception e) {
            return null;
        }
    }

    private String getAutocompleteBookId(JSONObject item) {
        try {
            String id = null;
            Object bookId = item.opt("bookId");
            if (bookId instanceof Number) {
                id = String.valueOf(((Number) bookId).longValue());
            } else if (bookId instanceof String s && !s.isBlank()) {
                id = s;
            }
            if (id != null && id.matches("\\d+")) return id;

            String bookUrl = item.optString("bookUrl");
            Matcher m = BOOK_SHOW_ID_PATTERN.matcher(bookUrl);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getAutocompleteAuthor(JSONObject item) {
        try {
            Object author = item.opt("author");
            if (author instanceof String s) return normalizeNull(s);
            if (author instanceof JSONObject obj) return normalizeNull(obj.optString("name"));
        } catch (Exception e) {
            log.warn("GoodReads: Failed to extract author from autocomplete item: {}", e.getMessage());
        }
        return null;
    }

    private String getSearchTerm(Book book, FetchMetadataRequest request) {
        if (request.getTitle() != null && !request.getTitle().isEmpty()) {
            if (request.getAuthor() != null && !request.getAuthor().isEmpty()) {
                return request.getTitle() + " " + request.getAuthor();
            }
            return request.getTitle();
        }
        return (book.getPrimaryFile() != null && book.getPrimaryFile().getFileName() != null && !book.getPrimaryFile().getFileName().isEmpty()
                ? BookUtils.cleanFileName(book.getPrimaryFile().getFileName())
                : null);
    }

    private Integer extractGoodReadsIdPreview(Element book) {
        try {
            Element bookTitle = book.select("a.bookTitle").first();
            if (bookTitle == null) {
                return null;
            }
            String href = bookTitle.attr("href");
            Matcher matcher = BOOK_SHOW_ID_PATTERN.matcher(href);
            if (matcher.find()) {
                return Integer.valueOf(matcher.group(1));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private List<String> extractAuthorsPreview(Element book) {
        List<String> authors = new ArrayList<>();
        try {
            Elements authorsElement = book.select("a.authorName");
            for (Element authorElement : authorsElement) {
                authors.add(authorElement.text());
            }
        } catch (Exception e) {
            log.warn("Error extracting author: {}", e.getMessage());
            return authors;
        }
        return authors;
    }

    private String extractTitlePreview(Element book) {
        try {
            Element link = book.select("a[title]").first();
            return link != null ? link.attr("title") : null;
        } catch (Exception e) {
            log.warn("Error extracting title: {}", e.getMessage());
            return null;
        }
    }

    private String extractThumbnailPreview(Element book) {
        try {
            Element img = book.selectFirst("img");
            if (img != null) {
                String src = img.attr("src");
                if (!src.isBlank()) {
                    return src;
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting thumbnail: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String goodreadsId) {
        log.info("GoodReads: Fetching detailed metadata for ID: {}", goodreadsId);
        try {
            Document document = fetchDoc(BASE_BOOK_URL + goodreadsId);
            return parseBookDetails(document, goodreadsId);
        } catch (Exception e) {
            log.error("Error fetching detailed metadata for GoodReads ID: {}", goodreadsId, e);
            return null;
        }
    }

    /**
     * Best-effort extraction of an author's details (bio, photo, GoodReads profile id) from a
     * GoodReads book page's apolloState {@code Contributor} node. Used to enrich the author
     * catalogue from a book that already has a GoodReads ID, sidestepping the unreliable
     * free-text author search. Returns {@code null} if the page is WAF-gated, has no
     * {@code __NEXT_DATA__}, or has no usable contributor.
     *
     * @param goodreadsBookId the numeric GoodReads book id (e.g. "13496")
     * @param authorNameHint  the catalogue author's name, used to pick the right contributor
     *                        on multi-author books; may be null
     */
    public AuthorSearchResult fetchAuthorFromBookPage(String goodreadsBookId, String authorNameHint) {
        if (goodreadsBookId == null || goodreadsBookId.isBlank()) {
            return null;
        }
        try {
            Document document = fetchDoc(BASE_BOOK_URL + goodreadsBookId);
            return extractAuthorFromBookDocument(document, authorNameHint);
        } catch (WafChallengeException e) {
            log.warn("GoodReads: WAF challenge extracting author from book {}", goodreadsBookId);
            return null;
        } catch (Exception e) {
            log.error("GoodReads: failed to extract author from book {}", goodreadsBookId, e);
            return null;
        }
    }

    /** A Goodreads author found by name: their numeric id and name as Goodreads spells it. */
    public record AuthorRef(String id, String name) {}

    /**
     * Goodreads authors whose name matches, found through the search box's autocomplete (JSON, and
     * not behind the WAF that gates the HTML pages): each result book carries its author's id.
     */
    public List<AuthorRef> searchAuthors(String name, int limit) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        try {
            String json = fetchJsonBody(BASE_AUTOCOMPLETE_URL + URLEncoder.encode(name, StandardCharsets.UTF_8));
            if (json == null || !json.startsWith("[")) {
                return List.of();
            }
            JSONArray items = new JSONArray(json);
            Map<String, AuthorRef> byId = new LinkedHashMap<>();
            for (int i = 0; i < items.length() && byId.size() < limit; i++) {
                JSONObject author = items.getJSONObject(i).optJSONObject("author");
                if (author == null) {
                    continue;
                }
                String id = author.has("id") ? String.valueOf(author.opt("id")) : null;
                String authorName = blankToNull(author.optString("name"));
                if (id != null && id.matches("\\d+") && authorName != null && namesMatch(authorName, name)) {
                    byId.putIfAbsent(id, new AuthorRef(id, authorName));
                }
            }
            return new ArrayList<>(byId.values());
        } catch (Exception e) {
            log.warn("GoodReads: author search failed for '{}': {}", name, e.getMessage());
            return List.of();
        }
    }

    /** An author's Goodreads page: name, full biography and photo. Null when it can't be read. */
    public AuthorSearchResult fetchAuthorPage(String goodreadsAuthorId) {
        if (goodreadsAuthorId == null || !goodreadsAuthorId.matches("\\d+")) {
            return null;
        }
        try {
            return extractAuthorPage(fetchDoc(BASE_AUTHOR_URL_PREFIX + "show/" + goodreadsAuthorId), goodreadsAuthorId);
        } catch (WafChallengeException e) {
            log.warn("GoodReads: WAF challenge on author page {}", goodreadsAuthorId);
            return null;
        } catch (Exception e) {
            log.warn("GoodReads: author page {} failed: {}", goodreadsAuthorId, e.getMessage());
            return null;
        }
    }

    AuthorSearchResult extractAuthorPage(Document doc, String goodreadsAuthorId) {
        Element nameElement = doc.selectFirst("h1.authorName [itemprop=name], h1.authorName");
        String name = nameElement != null ? blankToNull(nameElement.text().trim()) : null;
        if (name == null) {
            return null;
        }
        // The full biography sits in a hidden span beside the shortened one that's shown.
        Element bio = doc.selectFirst(".aboutAuthorInfo span[id=freeText" + "author" + goodreadsAuthorId + "]");
        if (bio == null) {
            bio = doc.selectFirst(".aboutAuthorInfo span[id^=freeTextContainer], .aboutAuthorInfo span[id^=freeText]");
        }
        String description = bio != null ? blankToNull(stripHtml(bio.html())) : null;
        Element photo = doc.selectFirst("img[itemprop=image]");
        String imageUrl = photo != null ? blankToNull(photo.absUrl("src")) : null;
        if (imageUrl != null && imageUrl.contains("nophoto")) {
            imageUrl = null;
        }
        return AuthorSearchResult.builder()
                .source(AuthorMetadataSource.GOODREADS)
                .name(name)
                .description(description)
                .imageUrl(imageUrl)
                .goodreadsId(goodreadsAuthorId)
                .build();
    }

    AuthorSearchResult extractAuthorFromBookDocument(Document document, String authorNameHint) {
        JSONObject apolloStateJson = getApolloState(document);
        if (apolloStateJson == null) {
            return null;
        }
        LinkedHashSet<String> keySet = getJsonKeys(apolloStateJson);

        JSONObject contributor = selectContributor(apolloStateJson, keySet, authorNameHint);
        if (contributor == null) {
            return null;
        }

        String name = blankToNull(contributor.optString("name"));
        String bio = stripHtml(firstNonBlankJsonField(contributor, "description"));
        String imageUrl = firstNonBlankJsonField(contributor, "profileImageUrl", "imageUrl", "image");
        String webUrl = blankToNull(contributor.optString("webUrl"));
        String goodreadsAuthorId = extractGoodreadsAuthorId(webUrl);

        if (isBlank(bio) && webUrl != null) {
            bio = fetchAuthorPageBio(webUrl);
        }

        if (isBlank(name) && isBlank(bio) && isBlank(imageUrl) && goodreadsAuthorId == null) {
            return null;
        }

        return AuthorSearchResult.builder()
                .source(AuthorMetadataSource.GOODREADS)
                .name(name)
                .description(isBlank(bio) ? null : bio)
                .imageUrl(imageUrl)
                .goodreadsId(goodreadsAuthorId)
                .build();
    }

    private JSONObject getApolloState(Document document) {
        JSONObject nextData = getJson(document);
        if (nextData == null) {
            return null;
        }
        try {
            return nextData.getJSONObject("props").getJSONObject("pageProps").getJSONObject("apolloState");
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject selectContributor(JSONObject apolloStateJson, LinkedHashSet<String> keySet, String nameHint) {
        List<String> keys = findKeysByPrefixAll(keySet, "Contributor:kca");
        if (keys.isEmpty()) {
            String single = findKeyByPrefix(keySet, "Contributor:kca");
            if (single != null) {
                keys = List.of(single);
            }
        }
        JSONObject firstContributor = null;
        for (String key : keys) {
            JSONObject node = apolloStateJson.optJSONObject(key);
            if (node == null) {
                continue;
            }
            if (firstContributor == null) {
                firstContributor = node;
            }
            if (nameHint != null && namesMatch(node.optString("name"), nameHint)) {
                return node;
            }
        }
        return firstContributor;
    }

    private boolean namesMatch(String a, String b) {
        String na = normalizeName(a);
        String nb = normalizeName(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    private String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        // Lowercase, treat any non-alphanumeric (punctuation, initials' dots) as a separator,
        // then collapse whitespace so "James S.A. Corey" == "James S. A. Corey".
        String cleaned = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]+", " ");
        return WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();
    }

    private String firstNonBlankJsonField(JSONObject node, String... fieldNames) {
        Iterator<String> it = node.keys();
        while (it.hasNext()) {
            String key = it.next();
            for (String field : fieldNames) {
                // GoodReads sometimes stores fields with GraphQL args, e.g. description({"stripped":true})
                if (key.equals(field) || key.startsWith(field + "(")) {
                    String value = blankToNull(node.optString(key));
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        String text = WHITESPACE_PATTERN.matcher(Jsoup.parse(html).text().trim()).replaceAll(" ");
        return text.isBlank() ? null : text;
    }

    private String extractGoodreadsAuthorId(String webUrl) {
        if (webUrl == null) {
            return null;
        }
        Matcher matcher = GOODREADS_AUTHOR_ID_PATTERN.matcher(webUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String fetchAuthorPageBio(String authorUrl) {
        // authorUrl comes out of the page payload, so treat it as untrusted: only ever follow it
        // back to GoodReads itself, never to an arbitrary (possibly internal) host.
        if (authorUrl == null || !authorUrl.startsWith(BASE_AUTHOR_URL_PREFIX)) {
            return null;
        }
        try {
            Document doc = fetchDoc(authorUrl);
            Element about = doc.selectFirst(".aboutAuthorInfo span[id^=freeText], .aboutAuthorInfo span");
            if (about != null && !about.text().isBlank()) {
                return stripHtml(about.html());
            }
            JSONObject apolloStateJson = getApolloState(doc);
            if (apolloStateJson != null) {
                LinkedHashSet<String> keys = getJsonKeys(apolloStateJson);
                JSONObject contributor = selectContributor(apolloStateJson, keys, null);
                if (contributor != null) {
                    return stripHtml(firstNonBlankJsonField(contributor, "description"));
                }
            }
        } catch (Exception e) {
            log.debug("GoodReads: author-page bio fallback failed for {}: {}", authorUrl, e.getMessage());
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // A single WAF challenge is often transient (rate/behavior based, not a hard IP ban) --
    // retry a couple of times with a short backoff before giving up and letting the caller
    // fall back to the shallower autocomplete preview (which is missing fields like asin).
    private static final int MAX_FETCH_ATTEMPTS = 3;

    // Once the retries are exhausted the gate is treated as a block: later page fetches skip the
    // network for the cooldown (callers fall back to autocomplete data) instead of each burning
    // another three challenged requests and making the block stickier.
    private Document fetchDoc(String url) {
        if (providerGuard.isBlocked(MetadataProvider.GoodReads)) {
            log.debug("GoodReads: skipping page fetch during WAF cooldown: {}", url);
            throw new WafChallengeException();
        }

        // The headless browser solves the WAF challenge itself (it already waited for it), so a
        // page that is still gated afterwards is a real block rather than something to retry.
        if (browserPageFetcher.isAvailable()) {
            providerGuard.awaitTurn(MetadataProvider.GoodReads);
            Optional<BrowserPageFetcher.FetchedPage> browserPage =
                    browserPageFetcher.fetch(url, Map.of(), html -> !isWafChallenge(200, html));
            if (browserPage.isPresent()) {
                BrowserPageFetcher.FetchedPage page = browserPage.get();
                if (!page.ready()) {
                    log.warn("GoodReads: WAF challenge persisted in the headless browser: {}", url);
                    providerGuard.markBlocked(MetadataProvider.GoodReads);
                    throw new WafChallengeException();
                }
                return Jsoup.parse(page.html(), page.url());
            }
        }

        WafChallengeException lastWafException = null;
        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            try {
                return fetchDocOnce(url);
            } catch (WafChallengeException e) {
                lastWafException = e;
                if (attempt < MAX_FETCH_ATTEMPTS) {
                    log.warn("GoodReads: WAF challenge fetching {} (attempt {}/{}), retrying...", url, attempt, MAX_FETCH_ATTEMPTS);
                    sleepBeforeRetry();
                }
            }
        }
        providerGuard.markBlocked(MetadataProvider.GoodReads);
        throw lastWafException;
    }

    private Document fetchDocOnce(String url) {
        providerGuard.awaitTurn(MetadataProvider.GoodReads);
        try {
            Connection.Response response = Jsoup.connect(url)
                    .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("accept-language", "en-US,en;q=0.9")
                    .header("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"macOS\"")
                    .header("sec-fetch-dest", "document")
                    .header("sec-fetch-mode", "navigate")
                    .header("sec-fetch-site", "none")
                    .header("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.GET)
                    .execute();

            if (isWafChallenge(response.statusCode(), response.body())) {
                throw new WafChallengeException();
            }
            return response.parse();
        } catch (WafChallengeException e) {
            throw e;
        } catch (IOException e) {
            log.error("Error fetching url: {}", url, e);
            throw new RuntimeException(e);
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 2500));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }

    private String fetchJsonBody(String url) {
        providerGuard.awaitTurn(MetadataProvider.GoodReads);
        try {
            Connection.Response response = Jsoup.connect(url)
                    .header("accept", "application/json,text/plain,*/*")
                    .header("accept-language", "en-US,en;q=0.9")
                    .header("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.GET)
                    .execute();

            if (!response.body().startsWith("[") && !response.body().startsWith("{")) {
                return null;
            }
            return response.body();
        } catch (IOException e) {
            log.warn("Error fetching JSON url: {}", url, e);
            return null;
        }
    }

    static boolean isWafChallenge(int statusCode, String html) {
        if (statusCode == 202) return true;
        // Real book pages served to a client holding a WAF token embed AWS WAF's challenge.js
        // (it refreshes the token), so a page carrying the Next.js payload is content, not a gate.
        if (html != null && html.contains("__NEXT_DATA__")) return false;
        return html != null && (html.contains("awsWafCookieDomainList")
                || html.contains("AwsWafIntegration")
                || html.contains("id=\"challenge-container\"")
                || html.contains("challenge.js"));
    }}
