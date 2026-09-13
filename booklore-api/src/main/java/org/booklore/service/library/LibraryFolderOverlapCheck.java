package org.booklore.service.library;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.LibraryRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Refuses a library folder that is, contains, or sits inside a folder another library (or the same
 * one) already has. Both libraries would import the books in the shared part, so each appears
 * twice, and renaming files into one library's folder structure moves them out from under the other.
 */
@Component
@RequiredArgsConstructor
public class LibraryFolderOverlapCheck {

    private final LibraryRepository libraryRepository;

    /**
     * @param libraryId   the library being edited, or null for a new one
     * @param keptFolders folders the library already has and keeps
     * @param newFolders  folders being added; only these are checked, so an overlap that predates
     *                    this check doesn't stop other changes to either library
     */
    public void assertNoOverlap(Long libraryId, Collection<String> keptFolders, Collection<String> newFolders) {
        List<Claimed> claimed = new ArrayList<>();
        for (LibraryEntity library : libraryRepository.findAll()) {
            if (Objects.equals(library.getId(), libraryId) || library.getLibraryPaths() == null) continue;
            for (LibraryPathEntity path : library.getLibraryPaths()) {
                claimed.add(new Claimed(path.getPath(), resolve(path.getPath()), library.getName()));
            }
        }
        for (String folder : keptFolders) {
            claimed.add(new Claimed(folder, resolve(folder), null));
        }

        for (String folder : newFolders) {
            Path candidate = resolve(folder);
            for (Claimed other : claimed) {
                String problem = describe(folder, candidate, other);
                if (problem != null) {
                    throw ApiError.GENERIC_BAD_REQUEST.createException(problem);
                }
            }
            claimed.add(new Claimed(folder, candidate, null));
        }
    }

    private static String describe(String folder, Path candidate, Claimed other) {
        String owner = other.libraryName() == null ? "this library" : "the library \"" + other.libraryName() + "\"";
        String reason = " A folder can only belong to one library, or its books are imported twice.";
        if (candidate.equals(other.resolved())) {
            return folder + " is already a folder of " + owner + "." + reason;
        }
        if (candidate.startsWith(other.resolved())) {
            return folder + " is inside " + other.folder() + ", a folder of " + owner + "." + reason;
        }
        if (other.resolved().startsWith(candidate)) {
            return folder + " contains " + other.folder() + ", a folder of " + owner + "."
                    + reason + " Add its other sub-folders one by one instead.";
        }
        return null;
    }

    /** The real location where it exists, so a link to another library's folder is caught too. */
    private static Path resolve(String folder) {
        Path path = Paths.get(folder).toAbsolutePath().normalize();
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException e) {
            return path;
        }
    }

    private record Claimed(String folder, Path resolved, String libraryName) {}
}
