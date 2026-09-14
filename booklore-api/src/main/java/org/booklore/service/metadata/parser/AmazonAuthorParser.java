package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Authors from Amazon: found through the author links in a book search, then read from their author
 * page. Uses the Amazon domain and cookie from the metadata settings, like book metadata does.
 */
@Service
@RequiredArgsConstructor
public class AmazonAuthorParser implements AuthorParser {

    private static final int MAX_RESULTS = 3;

    private final AmazonBookParser amazonBookParser;
    private final AppSettingService appSettingService;

    @Override
    public List<AuthorSearchResult> searchAuthors(String name, String region) {
        return amazonBookParser.searchAuthors(name, MAX_RESULTS).stream()
                .map(ref -> {
                    AuthorSearchResult page = amazonBookParser.fetchAuthorPage(ref.asin());
                    return page != null ? page : AuthorSearchResult.builder()
                            .source(AuthorMetadataSource.AMAZON).name(ref.name()).asin(ref.asin()).build();
                })
                .toList();
    }

    @Override
    public AuthorSearchResult getAuthorByAsin(String asin, String region) {
        return amazonBookParser.fetchAuthorPage(asin);
    }

    @Override
    public AuthorSearchResult quickSearch(String name, String region) {
        return amazonBookParser.searchAuthors(name, 1).stream()
                .findFirst()
                .map(ref -> amazonBookParser.fetchAuthorPage(ref.asin()))
                .orElse(null);
    }

    @Override
    public boolean isEnabled() {
        var settings = appSettingService.getAppSettings().getMetadataProviderSettings();
        return settings != null && settings.getAmazon() != null && settings.getAmazon().isEnabled();
    }
}
