package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.model.enums.MetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenLibraryParserTest {

    private HttpClient httpClient;
    private OpenLibraryParser parser;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        parser = new OpenLibraryParser(new ObjectMapper(), httpClient);
    }

    @Test
    void fetchMetadata_mapsSearchDocs() throws Exception {
        mockResponse("""
                {
                  "numFound": 1,
                  "docs": [
                    {
                      "key": "/works/OL12345W",
                      "title": "The Great Gatsby",
                      "author_name": ["F. Scott Fitzgerald"],
                      "first_publish_year": 1925,
                      "isbn": ["1234567890", "1234567890123"],
                      "cover_i": 12345,
                      "publisher": ["Charles Scribner's Sons"],
                      "language": ["eng"],
                      "number_of_pages_median": 180,
                      "subject": ["Fiction", "Classic"]
                    }
                  ]
                }
                """);

        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(), FetchMetadataRequest.builder()
                .title("The Great Gatsby")
                .author("Fitzgerald")
                .build());

        assertEquals(1, results.size());
        BookMetadata metadata = results.getFirst();
        assertEquals(MetadataProvider.OpenLibrary, metadata.getProvider());
        assertEquals("The Great Gatsby", metadata.getTitle());
        assertEquals(List.of("F. Scott Fitzgerald"), metadata.getAuthors());
        assertEquals(LocalDate.of(1925, 1, 1), metadata.getPublishedDate());
        assertEquals("1234567890", metadata.getIsbn10());
        assertEquals("1234567890123", metadata.getIsbn13());
        assertEquals("Charles Scribner's Sons", metadata.getPublisher());
        assertEquals("eng", metadata.getLanguage());
        assertEquals(180, metadata.getPageCount());
        assertTrue(metadata.getCategories().contains("Fiction"));
        assertEquals("https://covers.openlibrary.org/b/id/12345-L.jpg", metadata.getThumbnailUrl());
        assertEquals("https://openlibrary.org/works/OL12345W", metadata.getExternalUrl());
    }

    @Test
    void fetchMetadata_prioritizesIsbnSearch() throws Exception {
        mockResponse("{\"docs\": []}");

        parser.fetchMetadata(Book.builder().build(), FetchMetadataRequest.builder()
                .isbn("978-1-234-56789-7")
                .title("Ignored Title")
                .build());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));

        String uri = captor.getValue().uri().toString();
        assertTrue(uri.contains("isbn=9781234567897"));
        assertTrue(!uri.contains("title=Ignored"));
    }

    @Test
    void fetchTopMetadata_enrichesSearchResultWithWorkDetails() throws Exception {
        mockResponseSequence(
                """
                {
                  "docs": [
                    {
                      "key": "/works/OL1W",
                      "title": "Search Title",
                      "author_name": ["Author One"],
                      "first_publish_year": 1999,
                      "isbn": ["1234567890"],
                      "subject": ["Search Subject"]
                    }
                  ]
                }
                """,
                """
                {
                  "key": "/works/OL1W",
                  "title": "Work Title",
                  "description": {"type": "/type/text", "value": "<p>Work description.</p>"},
                  "first_publish_date": "2001",
                  "subjects": ["Work Subject", "Another Subject"],
                  "covers": [98765]
                }
                """,
                """
                {
                  "key": "/books/OL1M",
                  "number_of_pages": 321
                }
                """
        );

        BookMetadata metadata = parser.fetchTopMetadata(Book.builder().build(), FetchMetadataRequest.builder()
                .title("Search Title")
                .build());

        assertEquals("Work Title", metadata.getTitle());
        assertEquals("Work description.", metadata.getDescription());
        assertEquals(LocalDate.of(2001, 1, 1), metadata.getPublishedDate());
        assertEquals(List.of("Author One"), metadata.getAuthors());
        assertEquals("1234567890", metadata.getIsbn10());
        assertTrue(metadata.getCategories().contains("Work Subject"));
        assertEquals("https://covers.openlibrary.org/b/id/98765-L.jpg", metadata.getThumbnailUrl());
        assertEquals(321, metadata.getPageCount());
        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fetchAuthor_returnsBioPhotoAsinAndIds() throws Exception {
        mockResponseSequence(
                """
                {"docs":[
                  {"key":"OL1394865A","name":"Brandon Sanderson"},
                  {"key":"OL999A","name":"Someone Else"}
                ]}
                """,
                """
                {
                  "name": "Brandon Sanderson",
                  "bio": {"type": "/type/text", "value": "Brandon Sanderson is an American <b>epic fantasy</b> author."},
                  "photos": [-1, 6155669],
                  "remote_ids": {"amazon": "B001IGFHW6", "goodreads": "38550", "wikidata": "Q220584"}
                }
                """
        );

        AuthorSearchResult result = parser.fetchAuthor("Brandon Sanderson");

        assertNotNull(result);
        assertEquals(AuthorMetadataSource.OPEN_LIBRARY, result.getSource());
        assertEquals("Brandon Sanderson", result.getName());
        assertEquals("Brandon Sanderson is an American epic fantasy author.", result.getDescription());
        assertEquals("https://covers.openlibrary.org/a/id/6155669-L.jpg", result.getImageUrl());
        assertEquals("B001IGFHW6", result.getAsin());
        assertEquals("38550", result.getGoodreadsId());
        assertEquals("OL1394865A", result.getOpenlibraryId());
    }

    @Test
    void fetchAuthor_fallsBackToFirstDocWhenNoNameMatch_andDropsJunkAsin() throws Exception {
        mockResponseSequence(
                """
                {"docs":[{"key":"OL42A","name":"J. Random"}]}
                """,
                """
                {"name": "J. Random", "bio": "A writer.", "remote_ids": {"amazon": "not-an-asin"}}
                """
        );

        AuthorSearchResult result = parser.fetchAuthor("Random J");

        assertNotNull(result);
        assertEquals("OL42A", result.getOpenlibraryId());
        assertEquals("A writer.", result.getDescription());
        assertNull(result.getAsin());
    }

    @Test
    void fetchAuthor_returnsNullWhenNoDocs() throws Exception {
        mockResponse("{\"docs\":[]}");
        assertNull(parser.fetchAuthor("Nobody At All"));
    }

    @Test
    void fetchMetadata_returnsEmptyWhenNoTitleOrIsbn() {
        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(), FetchMetadataRequest.builder()
                .author("Author")
                .build());

        assertTrue(results.isEmpty());
        assertNull(parser.fetchTopMetadata(Book.builder().build(), FetchMetadataRequest.builder().build()));
    }

    private void mockResponse(String jsonBody) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.body()).thenReturn(jsonBody);
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Test
    void searchAuthorKeys_putsExactNamesFirst_thenTheMostProlific() throws Exception {
        mockResponseSequence("""
                {"docs":[
                  {"key":"OL8894763A","name":"Tessa S. Bailey","work_count":1},
                  {"key":"OL10599515A","name":"Tessa Bailey","work_count":0},
                  {"key":"OL13097747A","name":"Tessa Bailey","work_count":106}
                ]}
                """);

        assertEquals(List.of("OL13097747A", "OL10599515A"), parser.searchAuthorKeys("Tessa Bailey", 2));
    }

    @Test
    void fetchAuthorByKey_refusesAnythingButAnAuthorKey() {
        assertNull(parser.fetchAuthorByKey("../works/OL1W"));
        assertNull(parser.fetchAuthorByKey(null));
        verifyNoInteractions(httpClient);
    }

    private void mockResponseSequence(String... jsonBodies) throws IOException, InterruptedException {
        HttpResponse<String>[] responses = new HttpResponse[jsonBodies.length];
        for (int i = 0; i < jsonBodies.length; i++) {
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.body()).thenReturn(jsonBodies[i]);
            when(response.statusCode()).thenReturn(200);
            responses[i] = response;
        }
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(responses[0], Arrays.copyOfRange(responses, 1, responses.length));
    }
}
