package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Reading authors from Amazon's and Goodreads' pages. The samples copy the structure of the live
 * pages (checked September 2026), with the text shortened.
 */
class AuthorPageParsingTest {

    private final AmazonBookParser amazon = new AmazonBookParser(mock(AppSettingService.class), mock(MetadataProviderGuard.class), mock(BrowserPageFetcher.class));
    private final GoodReadsParser goodreads = new GoodReadsParser(mock(AppSettingService.class), mock(MetadataProviderGuard.class), mock(BrowserPageFetcher.class));

    @Test
    void amazonSearchResultsGiveTheMatchingAuthorsAsins() {
        Document doc = Jsoup.parse("""
                <div class="s-result-item">
                  <a href="/Tessa-Bailey/e/B00BKMOZYO?ref=sr_ntt_srch_lnk_1">Tessa Bailey</a>
                  <a href="/Tessa-Bailey/e/B00BKMOZYO?ref=sr_ntt_srch_lnk_2">Tessa Bailey</a>
                  <a href="/Annabel-Monaghan/e/B001JS6VY6?ref=sxin_15">Annabel Monaghan</a>
                  <a href="/stores/Tessa-Bailey/author/B0CXYZ1234?ref=ap_rdr">Tessa  Bailey</a>
                  <a href="/dp/B08L3NHJ88">It Happened One Summer</a>
                </div>""", "https://www.amazon.com/s?k=Tessa+Bailey");

        assertThat(amazon.extractAuthorRefs(doc, "Tessa Bailey", 5)).containsExactly(
                new AmazonBookParser.AuthorRef("B00BKMOZYO", "Tessa Bailey"),
                new AmazonBookParser.AuthorRef("B0CXYZ1234", "Tessa Bailey"));
    }

    @Test
    void amazonAuthorPageGivesTheBioAndPhoto() {
        Document doc = Jsoup.parse("""
                <html><head>
                <meta property="og:title" content="Tessa Bailey: books, biography, latest update">
                <meta property="og:image" content="https://m.media-amazon.com/images/S/amzn-author-media-prod/ksdoe04.jpg">
                </head><body>
                <div data-widgettype="AuthorBio" class="a-column">
                  <div class="AuthorBio__author-bio__author-picture__hz4cs"><img src="https://m.media-amazon.com/images/S/amzn-author-media-prod/ksdoe04._SX300_.jpg"></div>
                  <h2 class="AuthorBio__author-bio__title__CO2Dr">About the author</h2>
                  <div class="AuthorBio__author-bio__author-biography__WeqwH"><p>New York Times Bestselling author Tessa Bailey writes romance.</p><p>She lives on Long Island.</p></div>
                </div></body></html>""", "https://www.amazon.com/stores/author/B00BKMOZYO/about");

        AuthorSearchResult result = amazon.extractAuthorPage(doc, "B00BKMOZYO");

        assertThat(result.getSource()).isEqualTo(AuthorMetadataSource.AMAZON);
        assertThat(result.getName()).isEqualTo("Tessa Bailey");
        assertThat(result.getAsin()).isEqualTo("B00BKMOZYO");
        assertThat(result.getDescription()).isEqualTo("New York Times Bestselling author Tessa Bailey writes romance.\n\nShe lives on Long Island.");
        assertThat(result.getImageUrl()).isEqualTo("https://m.media-amazon.com/images/S/amzn-author-media-prod/ksdoe04.jpg");
    }

    @Test
    void amazonsSilhouetteForAuthorsWithoutAPhotoIsNotKept() {
        Document doc = Jsoup.parse("""
                <html><head><meta property="og:title" content="L. M. Montgomery: books, biography, latest update"></head><body>
                <div data-widgettype="AuthorBio"><img src="https://m.media-amazon.com/images/I/01Kv-W2ysOL._SX300_.png">
                <div class="AuthorBio__author-bio__author-biography__x"></div></div></body></html>""");

        AuthorSearchResult result = amazon.extractAuthorPage(doc, "B088TRT5MX");

        assertThat(result.getImageUrl()).isNull();
        assertThat(result.getDescription()).isNull();
    }

    @Test
    void amazonPageWithoutAnAuthorGivesNothing() {
        assertThat(amazon.extractAuthorPage(Jsoup.parse("<html><head><title>Page Not Found</title></head></html>"), "B00BKMOZYO")).isNull();
    }

    @Test
    void authorNamesMatchDespiteInitialsAndPunctuation() {
        assertThat(AmazonBookParser.authorNamesMatch("James S.A. Corey", "James S. A. Corey")).isTrue();
        assertThat(AmazonBookParser.authorNamesMatch("Tessa Bailey", "tessa bailey")).isTrue();
        assertThat(AmazonBookParser.authorNamesMatch("Annabel Monaghan", "Tessa Bailey")).isFalse();
    }

    @Test
    void goodreadsAuthorPagePrefersTheFullBioAndSkipsThePlaceholderPhoto() {
        Document doc = Jsoup.parse("""
                <h1 class="authorName"><span itemprop="name">Tessa Bailey</span></h1>
                <img alt="Tessa Bailey" itemprop="image" src="https://images.gr-assets.com/authors/1634304355p5/6953499.jpg" />
                <div class="aboutAuthorInfo">
                  <span id="freeTextContainerauthor6953499">New York Times Bestselling author Tessa Bailey can solve all...</span>
                  <span id="freeTextauthor6953499" style="display:none">New York Times Bestselling author Tessa Bailey can solve all problems except for her own.<br><br>She lives on Long Island.</span>
                </div>""", "https://www.goodreads.com/author/show/6953499");

        AuthorSearchResult result = goodreads.extractAuthorPage(doc, "6953499");

        assertThat(result.getSource()).isEqualTo(AuthorMetadataSource.GOODREADS);
        assertThat(result.getName()).isEqualTo("Tessa Bailey");
        assertThat(result.getGoodreadsId()).isEqualTo("6953499");
        assertThat(result.getDescription()).startsWith("New York Times Bestselling author Tessa Bailey can solve all problems except for her own.");
        assertThat(result.getDescription()).contains("She lives on Long Island.");
        assertThat(result.getImageUrl()).isEqualTo("https://images.gr-assets.com/authors/1634304355p5/6953499.jpg");

        Document noPhoto = Jsoup.parse("""
                <h1 class="authorName"><span itemprop="name">A Writer</span></h1>
                <img itemprop="image" src="https://s.gr-assets.com/assets/nophoto/user/u_200x266.png" />""");
        assertThat(goodreads.extractAuthorPage(noPhoto, "1").getImageUrl()).isNull();
    }
}
