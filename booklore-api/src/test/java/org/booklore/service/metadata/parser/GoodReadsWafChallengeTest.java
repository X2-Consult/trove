package org.booklore.service.metadata.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoodReadsWafChallengeTest {

    // Trimmed from the HTTP 202 interstitial GoodReads serves a plain HTTP client.
    private static final String WAF_INTERSTITIAL = """
            <!DOCTYPE html><html lang="en"><head><title></title>
            <script type="text/javascript">window.awsWafCookieDomainList = []; window.gokuProps = {"key":"AQIDAH"};</script>
            <script src="https://ea457862827c.aa0f107e.ap-southeast-1.token.awswaf.com/ea457862827c/ca63136f72fb/b63fc4339d15/challenge.js"></script>
            </head><body><div id="challenge-container"></div>
            <script type="text/javascript">AwsWafIntegration.getToken().then(() => { window.location.reload(true); });</script>
            </body></html>
            """;

    // A real book page, as served once the client holds an aws-waf-token: it still loads the
    // WAF SDK's challenge.js alongside the Next.js payload.
    private static final String BOOK_PAGE_WITH_WAF_SDK = """
            <!DOCTYPE html><html><head>
            <script src="https://b13c77965523.us-east-1.sdk.awswaf.com/b13c77965523/d42dcdc33310/challenge.js" data-nscript="afterInteractive"></script>
            </head><body>
            <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"apolloState":{}}}}</script>
            </body></html>
            """;

    // Trimmed from the 2026-09 search page (Next.js app router: no __NEXT_DATA__, but the WAF
    // SDK is still loaded). It renders the result list twice.
    static final String SEARCH_PAGE_WITH_WAF_SDK = """
            <!DOCTYPE html><html><head><title>Book search results for "Hot Passion" | Goodreads</title>
            <script src="https://b13c77965523.us-east-1.sdk.awswaf.com/b13c77965523/d42dcdc33310/challenge.js" data-nscript="afterInteractive"></script>
            </head><body>
            <ul class="Books" data-testid="book-list-item" role="list">
              <li><div><div class="Book" data-testid="book-item-kca://book/amzn1.gr.book.v3.OXxPaY0DckVflpZR">
                <div class="Book__cover"><a href="/book/show/57970038?ref=s_s" aria-label="Hot Passion in Another World"></a>
                  <img class="ResponsiveImage" src="https://images.example/57970038.jpg"></div>
                <div class="Book__details"><span class="Text Text__title3" data-testid="book-item-title"><a href="/book/show/57970038">Hot Passion in Another World</a></span>
                  <span class="BookAuthors" data-testid="book-item-contributors"><a class="ContributorLink" href="https://www.goodreads.com/author/show/1.Reed_James"><span class="ContributorLink__name" data-testid="name">Reed James</span></a></span></div>
              </div></div></li>
              <li><div><div class="Book" data-testid="book-item-kca://book/amzn1.gr.book.v3.hiAvfyq3nsjX8FN1">
                <div class="Book__details"><span class="Text Text__title3" data-testid="book-item-title"><a href="/book/show/250996870">Hot Passion Erupts in Another World</a></span>
                  <span class="BookAuthors" data-testid="book-item-contributors"><a class="ContributorLink" href="https://www.goodreads.com/author/show/1.Reed_James"><span class="ContributorLink__name" data-testid="name">Reed James</span></a></span></div>
              </div></div></li>
            </ul>
            <ul class="Books" data-testid="book-list-item" role="list">
              <li><div><div class="Book"><span data-testid="book-item-title"><a href="/book/show/57970038">Hot Passion in Another World</a></span></div></div></li>
              <li><div><div class="Book"><span data-testid="book-item-title"><a href="/book/show/250996870">Hot Passion Erupts in Another World</a></span></div></div></li>
            </ul>
            </body></html>
            """;

    @Test
    void searchPageLoadingTheWafSdkIsNotAChallenge() {
        assertThat(GoodReadsParser.isWafChallenge(200, SEARCH_PAGE_WITH_WAF_SDK)).isFalse();
    }

    @Test
    void interstitialIsAChallenge() {
        assertThat(GoodReadsParser.isWafChallenge(200, WAF_INTERSTITIAL)).isTrue();
        assertThat(GoodReadsParser.isWafChallenge(202, "")).isTrue();
    }

    @Test
    void bookPageLoadingTheWafSdkIsNotAChallenge() {
        assertThat(GoodReadsParser.isWafChallenge(200, BOOK_PAGE_WITH_WAF_SDK)).isFalse();
    }
}
