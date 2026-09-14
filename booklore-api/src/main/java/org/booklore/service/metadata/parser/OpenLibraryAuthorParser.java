package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Authors from Open Library's author search and author records. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenLibraryAuthorParser implements AuthorParser {

    private static final int MAX_RESULTS = 3;

    private final OpenLibraryParser openLibraryParser;
    private final AppSettingService appSettingService;

    @Override
    public List<AuthorSearchResult> searchAuthors(String name, String region) {
        try {
            return openLibraryParser.searchAuthorKeys(name, MAX_RESULTS).stream()
                    .map(openLibraryParser::fetchAuthorByKey)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Open Library author search failed for '{}': {}", name, e.getMessage());
            return List.of();
        }
    }

    @Override
    public AuthorSearchResult getAuthorByAsin(String asin, String region) {
        return null;
    }

    @Override
    public AuthorSearchResult getAuthor(AuthorSearchResult ref, String region) {
        return openLibraryParser.fetchAuthorByKey(ref.getOpenlibraryId());
    }

    @Override
    public AuthorSearchResult quickSearch(String name, String region) {
        return openLibraryParser.fetchAuthor(name);
    }

    @Override
    public boolean isEnabled() {
        var settings = appSettingService.getAppSettings().getMetadataProviderSettings();
        return settings != null && settings.getOpenLibrary() != null && settings.getOpenLibrary().isEnabled();
    }
}
