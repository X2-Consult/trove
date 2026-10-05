package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AppleBooksParserTest {

    // Shaped like a real iTunes Search API ebook result (kind "ebook", no wrapperType).
    private static final String SEARCH_RESULT = """
            {"resultCount":1,"results":[{"kind":"ebook","trackId":436142462,"trackName":"Without Fail",
             "artistName":"Lee Child","description":"<p>Jack Reacher&#39;s back.</p>","genres":["Hard-Boiled Mysteries","Books"],
             "releaseDate":"2008-09-04T07:00:00Z",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Publication125/v4/50/9781407070643.jpg/100x100bb.jpg",
             "trackViewUrl":"https://books.apple.com/nz/book/without-fail/id436142462?uo=4","language":"EN",
             "averageUserRating":4.5,"userRatingCount":1234}]}
            """;
    private static final String EMPTY = "{\"resultCount\":0,\"results\":[]}";

    private HttpClient httpClient;
    private MetadataProviderGuard guard;
    private AppleBooksParser parser;
    private final List<URI> requested = new ArrayList<>();

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        guard = mock(MetadataProviderGuard.class);
        AppSettingService appSettingService = mock(AppSettingService.class);
        MetadataProviderSettings providers = new MetadataProviderSettings();
        MetadataProviderSettings.AppleBooks apple = new MetadataProviderSettings.AppleBooks();
        apple.setEnabled(true);
        apple.setCountry("nz");
        providers.setAppleBooks(apple);
        AppSettings settings = mock(AppSettings.class);
        when(settings.getMetadataProviderSettings()).thenReturn(providers);
        when(appSettingService.getAppSettings()).thenReturn(settings);
        parser = new AppleBooksParser(new ObjectMapper(), appSettingService, httpClient, guard);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String... bodies) throws Exception {
        var answers = new ArrayList<HttpResponse<InputStream>>();
        for (String body : bodies) {
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            answers.add(response);
        }
        var stub = when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (HttpResponse<InputStream> answer : answers) {
            stub = stub.thenAnswer(inv -> {
                requested.add(((HttpRequest) inv.getArgument(0)).uri());
                return answer;
            });
        }
    }

    @Test
    void mapsASearchResult() throws Exception {
        respond(200, SEARCH_RESULT);

        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(), FetchMetadataRequest.builder().title("Without Fail").author("Lee Child").build());

        assertThat(results).singleElement().satisfies(m -> {
            assertThat(m.getProvider()).isEqualTo(MetadataProvider.AppleBooks);
            assertThat(m.getTitle()).isEqualTo("Without Fail");
            assertThat(m.getAuthors()).containsExactly("Lee Child");
            assertThat(m.getDescription()).isEqualTo("Jack Reacher's back.");
            assertThat(m.getPublishedDate()).isEqualTo(LocalDate.of(2008, 9, 4));
            assertThat(m.getCategories()).containsExactly("Hard-Boiled Mysteries", "Books");
            assertThat(m.getThumbnailUrl()).endsWith("/1400x1400bb.jpg");
            assertThat(m.getLanguage()).isEqualTo("en");
            assertThat(m.getApplebooksId()).isEqualTo("436142462");
            assertThat(m.getApplebooksRating()).isEqualTo(4.5);
            assertThat(m.getApplebooksReviewCount()).isEqualTo(1234);
        });
        assertThat(requested.getFirst().toString())
                .startsWith("https://itunes.apple.com/search?")
                .contains("term=Without%20Fail%20Lee%20Child").contains("entity=ebook").contains("country=NZ");
        verify(guard).awaitTurn(MetadataProvider.AppleBooks);
    }

    @Test
    void triesTheIsbnFirstThenFallsBackToSearching() throws Exception {
        respond(200, EMPTY, SEARCH_RESULT);

        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().isbn("978-0-399-14718-0").title("Without Fail").build());

        assertThat(results).hasSize(1);
        assertThat(requested).hasSize(2);
        assertThat(requested.get(0).toString()).startsWith("https://itunes.apple.com/lookup?").contains("isbn=9780399147180");
        assertThat(requested.get(1).toString()).startsWith("https://itunes.apple.com/search?");
    }

    @Test
    void backsOffWhenRateLimited() throws Exception {
        respond(429, "");

        assertThat(parser.fetchMetadata(Book.builder().build(), FetchMetadataRequest.builder().title("x").build())).isEmpty();
        verify(guard).markBlocked(MetadataProvider.AppleBooks);
    }

    @Test
    void skipsTheNetworkWhileBlocked() throws Exception {
        when(guard.isBlocked(MetadataProvider.AppleBooks)).thenReturn(true);

        assertThat(parser.fetchMetadata(Book.builder().build(), FetchMetadataRequest.builder().title("x").build())).isEmpty();
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void resizesArtworkUrls() {
        assertThat(AppleBooksParser.resizeArtworkUrl("https://x/a.jpg/100x100bb.jpg", 1400)).isEqualTo("https://x/a.jpg/1400x1400bb.jpg");
        assertThat(AppleBooksParser.resizeArtworkUrl("https://x/a.jpg", 1400)).isEqualTo("https://x/a.jpg");
    }
}
