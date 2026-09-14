package org.booklore.service;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.dto.request.AuthorMatchRequest;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.repository.AuthorRepository;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.DuckDuckGoCoverService;
import org.booklore.service.metadata.parser.AuthorParser;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.booklore.service.metadata.parser.OpenLibraryParser;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Author search and matching across several sources: Amazon, Goodreads, Open Library, Audnexus. */
@ExtendWith(MockitoExtension.class)
class AuthorMetadataSourcesTest {

    @Mock private AuthorRepository authorRepository;
    @Mock private AuditService auditService;
    @Mock private FileService fileService;
    @Mock private DuckDuckGoCoverService duckDuckGoCoverService;
    @Mock private AuthenticationService authenticationService;
    @Mock private GoodReadsParser goodReadsParser;
    @Mock private OpenLibraryParser openLibraryParser;
    @Mock private AuthorParser amazon;
    @Mock private AuthorParser goodreads;
    @Mock private AuthorParser openLibrary;

    private AuthorMetadataService service;
    private AuthorEntity author;

    @BeforeEach
    void setUp() {
        Map<AuthorMetadataSource, AuthorParser> parsers = new LinkedHashMap<>();
        parsers.put(AuthorMetadataSource.AMAZON, amazon);
        parsers.put(AuthorMetadataSource.GOODREADS, goodreads);
        parsers.put(AuthorMetadataSource.OPEN_LIBRARY, openLibrary);
        service = new AuthorMetadataService(authorRepository, parsers, auditService, fileService, duckDuckGoCoverService,
                authenticationService, goodReadsParser, openLibraryParser);
        lenient().when(amazon.isEnabled()).thenReturn(true);
        lenient().when(goodreads.isEnabled()).thenReturn(true);
        lenient().when(openLibrary.isEnabled()).thenReturn(true);

        author = new AuthorEntity();
        author.setId(7L);
        author.setName("Tessa Bailey");
        lenient().when(authorRepository.findById(7L)).thenReturn(Optional.of(author));
        lenient().when(authorRepository.save(any(AuthorEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static AuthorSearchResult result(AuthorMetadataSource source, String name) {
        return AuthorSearchResult.builder().source(source).name(name).description(name + " writes books.").build();
    }

    @Test
    void searchAsksEveryEnabledSourceAndKeepsTheirOrder() {
        when(goodreads.isEnabled()).thenReturn(false);
        when(amazon.searchAuthors("Tessa Bailey", "us")).thenReturn(List.of(result(AuthorMetadataSource.AMAZON, "Tessa Bailey")));
        when(openLibrary.searchAuthors("Tessa Bailey", "us")).thenReturn(List.of(result(AuthorMetadataSource.OPEN_LIBRARY, "Tessa Bailey")));

        List<AuthorSearchResult> results = service.searchAuthorMetadata("Tessa Bailey", "us");

        assertThat(results).extracting(AuthorSearchResult::getSource)
                .containsExactly(AuthorMetadataSource.AMAZON, AuthorMetadataSource.OPEN_LIBRARY);
        verify(goodreads, never()).searchAuthors(any(), any());
    }

    @Test
    void theCombinedListDropsOtherPeopleEmptyDuplicatesAndAudiblesCopiesOfAmazonAuthors() {
        AuthorSearchResult amazonAusten = AuthorSearchResult.builder().source(AuthorMetadataSource.AMAZON).name("Jane Austen").asin("B000APWOKO").imageUrl("a.jpg").build();
        AuthorSearchResult olAusten = AuthorSearchResult.builder().source(AuthorMetadataSource.OPEN_LIBRARY).name("Jane Austen").openlibraryId("OL21594A").description("Bio").build();
        AuthorSearchResult olEmpty = AuthorSearchResult.builder().source(AuthorMetadataSource.OPEN_LIBRARY).name("Jane Austen Society").openlibraryId("OL1617732A").build();
        AuthorSearchResult audibleCopy = AuthorSearchResult.builder().source(AuthorMetadataSource.AUDNEXUS).name("Jane Austen").asin("B000APWOKO").description("Bio").build();
        AuthorSearchResult audibleOther = AuthorSearchResult.builder().source(AuthorMetadataSource.AUDNEXUS).name("Jane Austen").asin("B0BTY272ZJ").description("Another").build();
        AuthorSearchResult wrongPerson = AuthorSearchResult.builder().source(AuthorMetadataSource.AUDNEXUS).name("Captain Wentworth's Diary").asin("B0013TTK9E").build();
        AuthorSearchResult goodreadsOnlyName = AuthorSearchResult.builder().source(AuthorMetadataSource.GOODREADS).name("Jane Austen").goodreadsId("1265").build();

        assertThat(AuthorMetadataService.tidySearchResults(
                List.of(amazonAusten, goodreadsOnlyName, olAusten, olEmpty, audibleCopy, audibleOther, wrongPerson), "Jane Austen"))
                .containsExactly(amazonAusten, goodreadsOnlyName, olAusten, audibleOther);
    }

    @Test
    void initialsInTheQueryDontCountAsTheSurname() {
        AuthorSearchResult full = AuthorSearchResult.builder().source(AuthorMetadataSource.OPEN_LIBRARY).name("Lucy Maud Montgomery").description("Bio").build();
        AuthorSearchResult other = AuthorSearchResult.builder().source(AuthorMetadataSource.AUDNEXUS).name("M. L. Longworth").description("Bio").build();

        assertThat(AuthorMetadataService.tidySearchResults(List.of(full, other), "L. M. Montgomery")).containsExactly(full);
    }

    @Test
    void everyPartOfTheNameMustMatch() {
        AuthorSearchResult tessa = AuthorSearchResult.builder().source(AuthorMetadataSource.AMAZON).name("Tessa Bailey").description("Bio").build();
        AuthorSearchResult anna = AuthorSearchResult.builder().source(AuthorMetadataSource.AUDNEXUS).name("Anna Bailey").description("Bio").build();
        AuthorSearchResult initials = AuthorSearchResult.builder().source(AuthorMetadataSource.GOODREADS).name("T. Bailey").description("Bio").build();

        assertThat(AuthorMetadataService.tidySearchResults(List.of(tessa, anna, initials), "Tessa Bailey")).containsExactly(tessa, initials);
    }

    @Test
    void oneFailingSourceDoesNotSinkTheSearch() {
        when(amazon.searchAuthors("Tessa Bailey", "us")).thenThrow(new IllegalStateException("blocked"));
        when(goodreads.searchAuthors("Tessa Bailey", "us")).thenReturn(List.of(result(AuthorMetadataSource.GOODREADS, "Tessa Bailey")));
        when(openLibrary.searchAuthors("Tessa Bailey", "us")).thenReturn(null);

        assertThat(service.searchAuthorMetadata("Tessa Bailey", "us")).extracting(AuthorSearchResult::getSource)
                .containsExactly(AuthorMetadataSource.GOODREADS);
    }

    @Test
    void matchingAGoodreadsResultFetchesItByGoodreadsIdAndSavesTheId() {
        AuthorSearchResult page = AuthorSearchResult.builder().source(AuthorMetadataSource.GOODREADS)
                .name("Tessa Bailey").description("Bio from Goodreads.").goodreadsId("6953499").build();
        when(goodreads.getAuthor(any(), any())).thenAnswer(inv ->
                "6953499".equals(((AuthorSearchResult) inv.getArgument(0)).getGoodreadsId()) ? page : null);
        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setSource(AuthorMetadataSource.GOODREADS);
        request.setGoodreadsId("6953499");

        service.matchAuthor(7L, request);

        assertThat(author.getDescription()).isEqualTo("Bio from Goodreads.");
        assertThat(author.getGoodreadsId()).isEqualTo("6953499");
    }

    @Test
    void aMatchWithoutABioKeepsTheOneTheAuthorHas() {
        author.setDescription("Written by hand.");
        author.setAsin("B00BKMOZYO");
        when(openLibrary.getAuthor(any(), any())).thenReturn(AuthorSearchResult.builder()
                .source(AuthorMetadataSource.OPEN_LIBRARY).name("Tessa Bailey").openlibraryId("OL13097747A").build());
        AuthorMatchRequest request = new AuthorMatchRequest();
        request.setSource(AuthorMetadataSource.OPEN_LIBRARY);
        request.setOpenlibraryId("OL13097747A");

        service.matchAuthor(7L, request);

        assertThat(author.getDescription()).isEqualTo("Written by hand.");
        assertThat(author.getAsin()).isEqualTo("B00BKMOZYO");
        assertThat(author.getOpenlibraryId()).isEqualTo("OL13097747A");
    }

    @Test
    void quickMatchSkipsAResultForSomeoneElse() {
        when(amazon.quickSearch("Tessa Bailey", "us")).thenReturn(result(AuthorMetadataSource.AMAZON, "Annabel Monaghan"));
        when(goodreads.quickSearch("Tessa Bailey", "us")).thenReturn(result(AuthorMetadataSource.GOODREADS, "Tessa Bailey"));

        service.quickMatchAuthor(7L, "us");

        // Amazon's hit was someone else; Goodreads' is used (Open Library is still asked for a photo).
        assertThat(author.getDescription()).isEqualTo("Tessa Bailey writes books.");
    }

    @Test
    void quickMatchFillsAMissingPhotoFromTheNextSource() {
        when(amazon.quickSearch("Tessa Bailey", "us")).thenReturn(AuthorSearchResult.builder()
                .source(AuthorMetadataSource.AMAZON).name("Tessa Bailey").asin("B00BKMOZYO").description("Amazon bio.").build());
        when(goodreads.quickSearch("Tessa Bailey", "us")).thenReturn(AuthorSearchResult.builder()
                .source(AuthorMetadataSource.GOODREADS).name("Tessa Bailey").goodreadsId("6953499")
                .description("Goodreads bio.").imageUrl("https://images.gr-assets.com/authors/6953499.jpg").build());

        service.quickMatchAuthor(7L, "us");

        assertThat(author.getDescription()).isEqualTo("Amazon bio.");
        assertThat(author.getAsin()).isEqualTo("B00BKMOZYO");
        assertThat(author.getGoodreadsId()).isEqualTo("6953499");
        verify(fileService).createAuthorThumbnailFromUrl(7L, "https://images.gr-assets.com/authors/6953499.jpg");
        verify(openLibrary, never()).quickSearch(any(), any());
    }

    @Test
    void quickMatchSkipsDisabledSources() {
        when(amazon.isEnabled()).thenReturn(false);
        when(goodreads.quickSearch("Tessa Bailey", "us")).thenReturn(result(AuthorMetadataSource.GOODREADS, "Tessa Bailey"));

        service.quickMatchAuthor(7L, "us");

        verify(amazon, never()).quickSearch(any(), any());
        assertThat(author.getDescription()).isEqualTo("Tessa Bailey writes books.");
    }
}
