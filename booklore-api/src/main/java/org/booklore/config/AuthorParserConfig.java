package org.booklore.config;

import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.metadata.parser.AmazonAuthorParser;
import org.booklore.service.metadata.parser.AudnexusAuthorParser;
import org.booklore.service.metadata.parser.AuthorParser;
import org.booklore.service.metadata.parser.GoodreadsAuthorParser;
import org.booklore.service.metadata.parser.OpenLibraryAuthorParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class AuthorParserConfig {

    /**
     * Author sources in the order quick match and auto match try them: Amazon and Goodreads have
     * bios and photos for most living authors, Open Library covers older and public-domain ones and
     * carries both sites' ids, and Audnexus has Audible's narrators and audiobook authors.
     */
    @Bean
    public Map<AuthorMetadataSource, AuthorParser> authorParserMap(AmazonAuthorParser amazon,
                                                                  GoodreadsAuthorParser goodreads,
                                                                  OpenLibraryAuthorParser openLibrary,
                                                                  AudnexusAuthorParser audnexus) {
        Map<AuthorMetadataSource, AuthorParser> parsers = new LinkedHashMap<>();
        parsers.put(AuthorMetadataSource.AMAZON, amazon);
        parsers.put(AuthorMetadataSource.GOODREADS, goodreads);
        parsers.put(AuthorMetadataSource.OPEN_LIBRARY, openLibrary);
        parsers.put(AuthorMetadataSource.AUDNEXUS, audnexus);
        return Collections.unmodifiableMap(parsers);
    }
}
