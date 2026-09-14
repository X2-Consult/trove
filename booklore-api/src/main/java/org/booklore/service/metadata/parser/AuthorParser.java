package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;

import java.util.List;

public interface AuthorParser {

    List<AuthorSearchResult> searchAuthors(String name, String region);

    AuthorSearchResult getAuthorByAsin(String asin, String region);

    AuthorSearchResult quickSearch(String name, String region);

    /**
     * The full record for a result the user picked, looked up by whichever id this source uses
     * (an ASIN, a Goodreads author id, an Open Library key).
     */
    default AuthorSearchResult getAuthor(AuthorSearchResult ref, String region) {
        return ref.getAsin() == null ? null : getAuthorByAsin(ref.getAsin(), region);
    }

    /** Whether the source is switched on in the metadata settings. */
    default boolean isEnabled() {
        return true;
    }
}
