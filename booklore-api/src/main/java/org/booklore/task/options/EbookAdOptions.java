package org.booklore.task.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EbookAdOptions {

    /** Only these books; all EPUBs when empty. */
    private List<Long> bookIds;
}
