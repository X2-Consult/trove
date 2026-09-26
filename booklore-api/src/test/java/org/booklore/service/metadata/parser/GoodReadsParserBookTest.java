package org.booklore.service.metadata.parser;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.configurationprocessor.json.JSONObject;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code Book:kca:} node selection + description-key handling in
 * {@link GoodReadsParser#parseBookDetails}. A book page's apolloState often holds several
 * {@code Book} nodes (page book + "readers also enjoyed" stubs); the parser must pick the
 * one with the real {@code details} block, and read the description whether GoodReads stored
 * it under {@code description} or {@code description({"stripped":true})}.
 */
class GoodReadsParserBookTest {

    private GoodReadsParser parser;

    @BeforeEach
    void setUp() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings settings = AppSettings.builder()
                .metadataPublicReviewsSettings(MetadataPublicReviewsSettings.builder().providers(Set.of()).build())
                .build();
        when(appSettingService.getAppSettings()).thenReturn(settings);
        parser = new GoodReadsParser(appSettingService, mock(MetadataProviderGuard.class), mock(BrowserPageFetcher.class));
    }

    private Document docWithApolloState(String apolloStateJson) {
        String nextData = "{\"props\":{\"pageProps\":{\"apolloState\":" + apolloStateJson + "}}}";
        return Jsoup.parse("<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + nextData + "</script></head><body></body></html>");
    }

    @Test
    void picksTheBookNodeWithDetails_notATitleOnlyStub() {
        Document doc = docWithApolloState("""
                {
                  "Book:kca://book/stub": { "title": "Some Other Book" },
                  "Book:kca://book/real": {
                    "title": "The Final Empire",
                    "description({\\"stripped\\":true})": "Brandon Sanderson's epic fantasy debut.",
                    "bookGenres": [],
                    "details": { "numPages": 541, "isbn13": "9780765311788", "asin": "B002GYI9C4" }
                  }
                }
                """);

        BookMetadata result = parser.parseBookDetails(doc, "68428");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("The Final Empire");
        assertThat(result.getDescription()).isEqualTo("Brandon Sanderson's epic fantasy debut.");
        assertThat(result.getIsbn13()).isEqualTo("9780765311788");
        assertThat(result.getAsin()).isEqualTo("B002GYI9C4");
        assertThat(result.getPageCount()).isEqualTo(541);
    }

    @Test
    void readsPlainDescriptionKeyToo() {
        Document doc = docWithApolloState("""
                {
                  "Book:kca://book/real": {
                    "title": "A Book",
                    "description": "Plain description key.",
                    "details": { "asin": "B00XXXXXXX" }
                  }
                }
                """);

        BookMetadata result = parser.parseBookDetails(doc, "1");

        assertThat(result.getDescription()).isEqualTo("Plain description key.");
        assertThat(result.getAsin()).isEqualTo("B00XXXXXXX");
    }

    @Test
    void seriesNameComesFromTheBooksOwnPrimarySeries_notTheFirstSeriesNodeOnThePage() {
        // Shape of The Way of Kings: in two series, and the page also carries a Series node from
        // a "readers also enjoyed" book, listed first.
        Document doc = docWithApolloState("""
                {
                  "Series:kca://series/other": { "title": "Some Other Series" },
                  "Series:kca://series/stormlight": { "title": "The Stormlight Archive" },
                  "Series:kca://series/cosmere": { "title": "The Cosmere Universe" },
                  "Book:kca://book/real": {
                    "title": "The Way of Kings",
                    "details": { "asin": "B003P2WO5E" },
                    "bookSeries": [
                      { "userPosition": "1", "series": { "__ref": "Series:kca://series/stormlight" } },
                      { "userPosition": "6", "series": { "__ref": "Series:kca://series/cosmere" } }
                    ]
                  }
                }
                """);

        BookMetadata result = parser.parseBookDetails(doc, "7235533");

        assertThat(result.getSeriesName()).isEqualTo("The Stormlight Archive");
        assertThat(result.getSeriesNumber()).isEqualTo(1f);
    }

    @Test
    void standaloneBookDoesNotPickUpAnotherBooksSeries() {
        Document doc = docWithApolloState("""
                {
                  "Series:kca://series/other": { "title": "Some Other Series" },
                  "Book:kca://book/real": { "title": "Project Hail Mary", "details": { "asin": "B08FHBV4ZX" }, "bookSeries": [] }
                }
                """);

        BookMetadata result = parser.parseBookDetails(doc, "54493401");

        assertThat(result.getSeriesName()).isNull();
        assertThat(result.getSeriesNumber()).isNull();
    }

    // Shape of a real /book/auto_complete?format=json item (the fallback used when book pages are WAF-gated).
    private static JSONObject autocompleteItem(String kcrPreviewUrl, boolean truncated) throws Exception {
        return new JSONObject("""
                {
                  "bookId": "54493401",
                  "bookTitleBare": "Project Hail Mary",
                  "title": "Project Hail Mary",
                  "author": { "name": "Andy Weir" },
                  "imageUrl": "https://i.gr-assets.com/images/S/books/1597695864i/54493401._SY75_.jpg",
                  "kcrPreviewUrl": %s,
                  "description": {
                    "html": "Ryland Grace is the sole survivor on a desperate, last-chance mission\\u2026",
                    "truncated": %s,
                    "fullContentUrl": "https://www.goodreads.com/book/show/54493401-project-hail-mary"
                  }
                }
                """.formatted(kcrPreviewUrl == null ? "null" : "\"" + kcrPreviewUrl + "\"", truncated));
    }

    @Test
    void autocompleteFallback_takesAsinFromKindlePreviewLink() throws Exception {
        BookMetadata result = parser.mapAutocompleteItem(autocompleteItem(
                "https://read.amazon.com.au/kp/embed?asin=B08FFJS3YW&ref=x_gr_w_preview_new_nf_story_au-20&preview=inline", true), "54493401");

        assertThat(result.getAsin()).isEqualTo("B08FFJS3YW");
        assertThat(result.getTitle()).isEqualTo("Project Hail Mary");
    }

    @Test
    void autocompleteFallback_noPreviewLinkMeansNoAsin() throws Exception {
        BookMetadata result = parser.mapAutocompleteItem(autocompleteItem(null, true), "54493401");

        assertThat(result.getAsin()).isNull();
    }

    @Test
    void autocompleteFallback_dropsTruncatedDescription_keepsCompleteOne() throws Exception {
        assertThat(parser.mapAutocompleteItem(autocompleteItem(null, true), "54493401").getDescription()).isNull();
        assertThat(parser.mapAutocompleteItem(autocompleteItem(null, false), "54493401").getDescription())
                .startsWith("Ryland Grace is the sole survivor");
    }

    @Test
    void readsTheNextJsSearchResultsListOnce() {
        Document doc = Jsoup.parse(GoodReadsWafChallengeTest.SEARCH_PAGE_WITH_WAF_SDK, "https://www.goodreads.com/search?q=Hot+Passion");

        var rows = GoodReadsParser.searchResultRows(doc);

        assertThat(rows).hasSize(2);
        assertThat(parser.extractGoodReadsIdPreview(rows.get(0))).isEqualTo(57970038);
        assertThat(parser.extractTitlePreview(rows.get(0))).isEqualTo("Hot Passion in Another World");
        assertThat(parser.extractAuthorsPreview(rows.get(0))).containsExactly("Reed James");
        assertThat(parser.extractThumbnailPreview(rows.get(0))).isEqualTo("https://images.example/57970038.jpg");
        assertThat(parser.extractGoodReadsIdPreview(rows.get(1))).isEqualTo(250996870);
    }

    @Test
    void readsTheClassicSearchResultsTable() {
        Document doc = Jsoup.parse("""
                <table class="tableList"><tr itemtype="http://schema.org/Book">
                  <td><a title="Dune" href="/book/show/44767458-dune"><img src="https://images.example/dune.jpg"></a></td>
                  <td><a class="bookTitle" href="/book/show/44767458-dune?from_search=true">Dune</a>
                      <a class="authorName" href="/author/show/58.Frank_Herbert"><span>Frank Herbert</span></a></td>
                </tr></table>
                """);

        var rows = GoodReadsParser.searchResultRows(doc);

        assertThat(rows).hasSize(1);
        assertThat(parser.extractGoodReadsIdPreview(rows.get(0))).isEqualTo(44767458);
        assertThat(parser.extractTitlePreview(rows.get(0))).isEqualTo("Dune");
        assertThat(parser.extractAuthorsPreview(rows.get(0))).containsExactly("Frank Herbert");
    }
}
