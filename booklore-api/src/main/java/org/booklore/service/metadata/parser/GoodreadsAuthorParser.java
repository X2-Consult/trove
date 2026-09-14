package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Authors from Goodreads: found through the search box's autocomplete, then read from their author
 * page. When the page is gated by Goodreads' bot check, the result still carries the name and id.
 */
@Service
@RequiredArgsConstructor
public class GoodreadsAuthorParser implements AuthorParser {

    private static final int MAX_RESULTS = 3;

    private final GoodReadsParser goodReadsParser;
    private final AppSettingService appSettingService;

    @Override
    public List<AuthorSearchResult> searchAuthors(String name, String region) {
        return goodReadsParser.searchAuthors(name, MAX_RESULTS).stream()
                .map(ref -> {
                    AuthorSearchResult page = goodReadsParser.fetchAuthorPage(ref.id());
                    return page != null ? page : AuthorSearchResult.builder()
                            .source(AuthorMetadataSource.GOODREADS).name(ref.name()).goodreadsId(ref.id()).build();
                })
                .toList();
    }

    @Override
    public AuthorSearchResult getAuthorByAsin(String asin, String region) {
        return null;
    }

    @Override
    public AuthorSearchResult getAuthor(AuthorSearchResult ref, String region) {
        return goodReadsParser.fetchAuthorPage(ref.getGoodreadsId());
    }

    @Override
    public AuthorSearchResult quickSearch(String name, String region) {
        return goodReadsParser.searchAuthors(name, 1).stream()
                .findFirst()
                .map(ref -> goodReadsParser.fetchAuthorPage(ref.id()))
                .orElse(null);
    }

    @Override
    public boolean isEnabled() {
        var settings = appSettingService.getAppSettings().getMetadataProviderSettings();
        return settings != null && settings.getGoodReads() != null && settings.getGoodReads().isEnabled();
    }
}
