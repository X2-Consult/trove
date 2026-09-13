package org.booklore.service.library;

import org.booklore.exception.APIException;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryFolderOverlapCheckTest {

    @Mock
    private LibraryRepository libraryRepository;

    @InjectMocks
    private LibraryFolderOverlapCheck check;

    @TempDir
    Path root;

    private String hayden;
    private final List<LibraryEntity> libraries = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        hayden = Files.createDirectories(root.resolve("Hayden_Books")).toString();
        Files.createDirectories(root.resolve("Mikes_Books"));
        libraries.add(library(2L, "Hayden", hayden));
        when(libraryRepository.findAll()).thenReturn(libraries);
    }

    private static LibraryEntity library(Long id, String name, String... folders) {
        LibraryEntity library = LibraryEntity.builder().id(id).name(name).libraryPaths(new ArrayList<>()).build();
        for (String folder : folders) {
            library.getLibraryPaths().add(LibraryPathEntity.builder().path(folder).library(library).build());
        }
        return library;
    }

    @Test
    void refusesAFolderThatContainsAnotherLibrarysFolder() {
        assertThatThrownBy(() -> check.assertNoOverlap(null, List.of(), List.of(root.toString())))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("contains " + hayden)
                .hasMessageContaining("\"Hayden\"");
    }

    @Test
    void refusesAFolderInsideAnotherLibrarysFolder() {
        String inside = root.resolve("Hayden_Books/Tolkien").toString();

        assertThatThrownBy(() -> check.assertNoOverlap(null, List.of(), List.of(inside)))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("is inside " + hayden);
    }

    @Test
    void refusesTheSameFolderWrittenDifferently() {
        String sameFolder = root.resolve("Mikes_Books/../Hayden_Books/").toString();

        assertThatThrownBy(() -> check.assertNoOverlap(null, List.of(), List.of(sameFolder)))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("already a folder of the library \"Hayden\"");
    }

    @Test
    void refusesALinkToAnotherLibrarysFolder() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("shortcut"), Path.of(hayden));

        assertThatThrownBy(() -> check.assertNoOverlap(null, List.of(), List.of(link.toString())))
                .isInstanceOf(APIException.class);
    }

    @Test
    void allowsASiblingFolderWithASimilarName() {
        String sibling = root.resolve("Hayden_Books_Old").toString();

        assertThatCode(() -> check.assertNoOverlap(null, List.of(), List.of(sibling))).doesNotThrowAnyException();
    }

    @Test
    void refusesNestedFoldersWithinOneLibrary() {
        String mikes = root.resolve("Mikes_Books").toString();
        String nested = root.resolve("Mikes_Books/Comics").toString();

        assertThatThrownBy(() -> check.assertNoOverlap(3L, List.of(mikes), List.of(nested)))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("a folder of this library");
    }

    @Test
    void anOverlapFromBeforeTheCheckDoesNotBlockEditingEitherLibrary() {
        libraries.add(library(1L, "Internal Books", root.toString()));

        // Renaming Internal Books, or Hayden, adds no folders, so nothing is refused.
        assertThatCode(() -> check.assertNoOverlap(1L, List.of(root.toString()), List.of())).doesNotThrowAnyException();
        assertThatCode(() -> check.assertNoOverlap(2L, List.of(hayden), List.of())).doesNotThrowAnyException();
    }

    @Test
    void theLibraryBeingEditedIsNotComparedWithItsOwnSavedFolders() {
        String second = root.resolve("Mikes_Books").toString();

        assertThatCode(() -> check.assertNoOverlap(2L, List.of(hayden), List.of(second))).doesNotThrowAnyException();
    }
}
