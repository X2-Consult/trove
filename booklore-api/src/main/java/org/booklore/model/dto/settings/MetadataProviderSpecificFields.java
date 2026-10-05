package org.booklore.model.dto.settings;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MetadataProviderSpecificFields {
    private Boolean asin;
    private Boolean amazonRating;
    private Boolean amazonReviewCount;
    private Boolean googleId;
    private Boolean goodreadsId;
    private Boolean goodreadsRating;
    private Boolean goodreadsReviewCount;
    private Boolean hardcoverId;
    private Boolean hardcoverBookId;
    private Boolean hardcoverRating;
    private Boolean hardcoverReviewCount;
    private Boolean comicvineId;
    private Boolean lubimyczytacId;
    private Boolean lubimyczytacRating;
    private Boolean ranobedbId;
    private Boolean applebooksId;
    private Boolean ranobedbRating;
    private Boolean applebooksRating;
    private Boolean audibleId;
    private Boolean audibleRating;
    private Boolean audibleReviewCount;
    private Boolean applebooksReviewCount;
}
