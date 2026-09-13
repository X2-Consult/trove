package org.booklore.model.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookEntityLibraryPathTest {

    @Test
    void movingABookToAnotherFolderMovesAllItsFiles() {
        LibraryPathEntity oldFolder = LibraryPathEntity.builder().id(1L).path("/library").build();
        LibraryPathEntity newFolder = LibraryPathEntity.builder().id(2L).path("/library/Hayden_Books").build();
        BookFileEntity epub = BookFileEntity.builder().id(10L).libraryPathId(1L).isBookFormat(true).build();
        BookFileEntity cover = BookFileEntity.builder().id(11L).libraryPathId(1L).isBookFormat(false).build();
        BookEntity book = BookEntity.builder().id(5L).libraryPath(oldFolder).bookFiles(List.of(epub, cover)).build();

        book.setLibraryPath(newFolder);

        assertThat(book.getLibraryPath()).isSameAs(newFolder);
        assertThat(List.of(epub, cover)).extracting(BookFileEntity::getLibraryPathId).containsOnly(2L);
    }
}
