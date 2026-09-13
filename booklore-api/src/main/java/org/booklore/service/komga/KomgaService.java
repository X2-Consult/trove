package org.booklore.service.komga;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.exception.ApiError;
import org.booklore.mapper.komga.KomgaMapper;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.dto.komga.*;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.MagicShelfService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.service.reader.PdfReaderService;
import org.booklore.service.restriction.ContentRestrictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KomgaService {

    private static final Pattern NON_ALPHANUMERIC_PATTERN = Pattern.compile("[^a-z0-9]+");
    private final BookRepository bookRepository;
    private final LibraryRepository libraryRepository;
    private final KomgaMapper komgaMapper;
    private final MagicShelfService magicShelfService;
    private final CbxReaderService cbxReaderService;
    private final PdfReaderService pdfReaderService;
    private final AppSettingService appSettingService;
    private final AuthenticationService authenticationService;
    private final UserRepository userRepository;
    private final ContentRestrictionService contentRestrictionService;

    /**
     * The Trove user behind the OPDS account making the request. Komga clients sign in with OPDS
     * accounts, each belonging to a Trove user, and see what that user would in the app: their
     * libraries, less anything their content restrictions hide. Admins see everything.
     */
    private record Viewer(Long userId, boolean admin, Set<Long> libraryIds) {
        boolean canSeeLibrary(Long libraryId) {
            return admin || libraryIds.contains(libraryId);
        }
    }

    private Viewer viewer() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        Long userId = details.getOpdsUserV2() != null ? details.getOpdsUserV2().getUserId() : null;
        if (userId == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }
        BookLoreUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));
        boolean admin = user.getPermissions() != null && user.getPermissions().isPermissionAdmin();
        Set<Long> libraryIds = user.getLibraries() == null ? Set.of()
                : user.getLibraries().stream().map(LibraryEntity::getId).collect(Collectors.toSet());
        return new Viewer(userId, admin, libraryIds);
    }

    private List<BookEntity> visible(Viewer viewer, List<BookEntity> books) {
        if (viewer.admin()) {
            return books;
        }
        List<BookEntity> inLibraries = books.stream()
                .filter(book -> book.getLibrary() != null && viewer.canSeeLibrary(book.getLibrary().getId()))
                .collect(Collectors.toList());
        return contentRestrictionService.applyRestrictions(inLibraries, viewer.userId());
    }

    private BookEntity visibleBook(Long bookId) {
        // A book the user can't see is reported exactly like one that doesn't exist.
        return bookRepository.findById(bookId)
                .filter(book -> !visible(viewer(), List.of(book)).isEmpty())
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
    }

    public List<KomgaLibraryDto> getAllLibraries() {
        Viewer viewer = viewer();
        return libraryRepository.findAll().stream()
                .filter(library -> viewer.canSeeLibrary(library.getId()))
                .map(komgaMapper::toKomgaLibraryDto)
                .collect(Collectors.toList());
    }

    public KomgaLibraryDto getLibraryById(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .filter(found -> viewer().canSeeLibrary(found.getId()))
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        return komgaMapper.toKomgaLibraryDto(library);
    }

    public KomgaPageableDto<KomgaSeriesDto> getAllSeries(Long libraryId, int page, int size, boolean unpaged) {
        log.debug("Getting all series for libraryId: {}, page: {}, size: {}", libraryId, page, size);
        
        Viewer viewer = viewer();
        // Check if we should group unknown series
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();
        
        // Get distinct series names directly from database (MUCH faster than loading all books).
        // That only works for admins: anyone else's series come from the books they can see.
        List<String> sortedSeriesNames;
        Map<String, List<BookEntity>> visibleSeries = null;
        if (!viewer.admin()) {
            List<BookEntity> books = libraryId == null ? bookRepository.findAllWithMetadata()
                    : viewer.canSeeLibrary(libraryId) ? bookRepository.findAllWithMetadataByLibraryId(libraryId) : List.of();
            visibleSeries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (BookEntity book : visible(viewer, books)) {
                visibleSeries.computeIfAbsent(komgaMapper.getBookSeriesName(book), k -> new ArrayList<>()).add(book);
            }
            sortedSeriesNames = new ArrayList<>(visibleSeries.keySet());
        } else if (groupUnknown) {
            // Use optimized query that groups books without series as "Unknown Series"
            if (libraryId != null) {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesGroupedByLibraryId(
                    libraryId, komgaMapper.getUnknownSeriesName());
            } else {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesGrouped(
                    komgaMapper.getUnknownSeriesName());
            }
        } else {
            // Use query that gives each book without series its own entry
            if (libraryId != null) {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesUngroupedByLibraryId(libraryId);
            } else {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesUngrouped();
            }
        }
        
        log.debug("Found {} distinct series names from database (optimized)", sortedSeriesNames.size());
        
        // Calculate pagination
        int totalElements = sortedSeriesNames.size();
        List<String> pageSeriesNames;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            pageSeriesNames = sortedSeriesNames;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            pageSeriesNames = sortedSeriesNames.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }
        
        // Now load books only for the series on this page (optimized - only loads what's needed)
        List<KomgaSeriesDto> content = new ArrayList<>();
        for (String seriesName : pageSeriesNames) {
            try {
                // Load only the books for this specific series
                List<BookEntity> seriesBooks;
                if (visibleSeries != null) {
                    seriesBooks = visibleSeries.get(seriesName);
                } else if (libraryId != null) {
                    if (groupUnknown) {
                        seriesBooks = bookRepository.findBooksBySeriesNameGroupedByLibraryId(
                            seriesName, libraryId, komgaMapper.getUnknownSeriesName());
                    } else {
                        seriesBooks = bookRepository.findBooksBySeriesNameUngroupedByLibraryId(
                            seriesName, libraryId);
                    }
                } else {
                    // For all libraries, need to load all books and filter (less common case)
                    List<BookEntity> allBooks = bookRepository.findAllWithMetadata();
                    seriesBooks = allBooks.stream()
                            .filter(book -> komgaMapper.getBookSeriesName(book).equals(seriesName))
                            .collect(Collectors.toList());
                }
                
                if (!seriesBooks.isEmpty()) {
                    Long libId = seriesBooks.get(0).getLibrary().getId();
                    KomgaSeriesDto seriesDto = komgaMapper.toKomgaSeriesDto(seriesName, libId, seriesBooks);
                    if (seriesDto != null) {
                        content.add(seriesDto);
                    }
                }
            } catch (Exception e) {
                log.error("Error mapping series: {}", seriesName, e);
            }
        }
        
        log.debug("Mapped {} series DTOs for this page", content.size());
        
        return KomgaPageableDto.<KomgaSeriesDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaSeriesDto getSeriesById(String seriesId) {
        // Parse seriesId to extract library and series name
        String[] parts = seriesId.split("-", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid series ID");
        }
        
        Long libraryId = Long.parseLong(parts[0]);
        String seriesSlug = parts[1];
        
        // Get books matching the series - optimized to query by series name
        List<BookEntity> allSeriesBooks = visible(viewer(), bookRepository.findAllWithMetadataByLibraryId(libraryId));
        
        // Find the series name that matches this slug
        List<BookEntity> seriesBooks = allSeriesBooks.stream()
                .filter(book -> {
                    String bookSeriesName = komgaMapper.getBookSeriesName(book);
                    String bookSeriesSlug = NON_ALPHANUMERIC_PATTERN.matcher(bookSeriesName.toLowerCase()).replaceAll("-");
                    return bookSeriesSlug.equals(seriesSlug);
                })
                .collect(Collectors.toList());
        
        if (seriesBooks.isEmpty()) {
            throw new RuntimeException("Series not found");
        }
        
        String seriesName = komgaMapper.getBookSeriesName(seriesBooks.get(0));
        
        return komgaMapper.toKomgaSeriesDto(seriesName, libraryId, seriesBooks);
    }

    public KomgaPageableDto<KomgaBookDto> getBooksBySeries(String seriesId, int page, int size, boolean unpaged) {
        // Parse seriesId to extract library and series name
        String[] parts = seriesId.split("-", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid series ID");
        }
        
        Long libraryId = Long.parseLong(parts[0]);
        String seriesSlug = parts[1];
        
        // Get all books for the library once
        List<BookEntity> allBooks = visible(viewer(), bookRepository.findAllWithMetadataByLibraryId(libraryId));
        
        // Filter and sort books for this series
        List<BookEntity> seriesBooks = allBooks.stream()
                .filter(book -> {
                    String bookSeriesName = komgaMapper.getBookSeriesName(book);
                    String bookSeriesSlug = NON_ALPHANUMERIC_PATTERN.matcher(bookSeriesName.toLowerCase()).replaceAll("-");
                    return bookSeriesSlug.equals(seriesSlug);
                })
                .sorted(Comparator.comparing(book -> {
                    BookMetadataEntity metadata = book.getMetadata();
                    return metadata != null && metadata.getSeriesNumber() != null 
                         ? metadata.getSeriesNumber() 
                         : 0f;
                }))
                .collect(Collectors.toList());
        
        // Handle unpaged mode
        int totalElements = seriesBooks.size();
        List<KomgaBookDto> content;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            // Return all books without pagination
            content = seriesBooks.stream()
                    .map(book -> komgaMapper.toKomgaBookDto(book))
                    .collect(Collectors.toList());
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            // Paginate
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            content = seriesBooks.subList(fromIndex, toIndex).stream()
                    .map(book -> komgaMapper.toKomgaBookDto(book))
                    .collect(Collectors.toList());
            actualPage = page;
            actualSize = size;
        }
        
        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaPageableDto<KomgaBookDto> getAllBooks(Long libraryId, int page, int size) {
        List<BookEntity> books;
        
        if (libraryId != null) {
            books = bookRepository.findAllWithMetadataByLibraryId(libraryId);
        } else {
            books = bookRepository.findAllWithMetadata();
        }
        books = visible(viewer(), books);
        
        // Manual pagination
        int totalElements = books.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        
        List<KomgaBookDto> content = books.subList(fromIndex, toIndex).stream()
                .map(book -> komgaMapper.toKomgaBookDto(book))
                .collect(Collectors.toList());
        
        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(page)
                .size(size)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(page >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaBookDto getBookById(Long bookId) {
        return komgaMapper.toKomgaBookDto(visibleBook(bookId));
    }

    public List<KomgaPageDto> getBookPages(Long bookId) {
        BookEntity book = visibleBook(bookId);
        
        BookMetadataEntity metadata = book.getMetadata();
        Integer pageCount = metadata != null && metadata.getPageCount() != null ? metadata.getPageCount() : 0;
        
        List<KomgaPageDto> pages = new ArrayList<>();
        if (pageCount > 0) {
            for (int i = 1; i <= pageCount; i++) {
                pages.add(KomgaPageDto.builder()
                        .number(i)
                        .fileName("page-" + i)
                        .mediaType("image/jpeg")
                        .build());
            }
        }
        
        return pages;
    }

    private Map<String, List<BookEntity>> groupBooksBySeries(List<BookEntity> books) {
        Map<String, List<BookEntity>> seriesMap = new HashMap<>();
        
        for (BookEntity book : books) {
            String seriesName = komgaMapper.getBookSeriesName(book);
            seriesMap.computeIfAbsent(seriesName, k -> new ArrayList<>()).add(book);
        }
        
        return seriesMap;
    }
    
    public KomgaPageableDto<KomgaCollectionDto> getCollections(int page, int size, boolean unpaged) {
        log.debug("Getting collections, page: {}, size: {}, unpaged: {}", page, size, unpaged);
        
        List<MagicShelf> magicShelves = magicShelfService.getUserShelves();
        log.debug("Found {} magic shelves", magicShelves.size());
        
        // Convert to collection DTOs - for now, series count is 0 since we don't have 
        // the series filter implementation
        List<KomgaCollectionDto> allCollections = magicShelves.stream()
                .map(shelf -> komgaMapper.toKomgaCollectionDto(shelf, 0))
                .sorted(Comparator.comparing(KomgaCollectionDto::getName))
                .collect(Collectors.toList());
        
        log.debug("Mapped to {} collection DTOs", allCollections.size());
        
        // Handle unpaged mode
        int totalElements = allCollections.size();
        List<KomgaCollectionDto> content;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            content = allCollections;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            // Paginate
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            content = allCollections.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }
        
        return KomgaPageableDto.<KomgaCollectionDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }
    
    public Resource getBookPageImage(Long bookId, Integer pageNumber, boolean convertToPng) throws IOException {
        log.debug("Getting page {} from book {} (convert to PNG: {})", pageNumber, bookId, convertToPng);
        
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found: " + bookId));

        boolean isPDF = book.getPrimaryBookFile().getBookType() == BookFileType.PDF;
     
        // Stream the page to a ByteArrayOutputStream
        // streamPageImage will throw if page does not exist
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Make sure pages are cached
        if (isPDF) {
            pdfReaderService.getAvailablePages(bookId);
            pdfReaderService.streamPageImage(bookId, pageNumber, outputStream);
        } else {
            cbxReaderService.getAvailablePages(bookId);
            cbxReaderService.streamPageImage(bookId, pageNumber, outputStream);
        }
        
        byte[] imageData = outputStream.toByteArray();
        
        // If conversion to PNG is requested, convert the image
        if (convertToPng) {
            imageData = convertImageToPng(imageData);
        }
        
        return new ByteArrayResource(imageData);
    }
    
    private byte[] convertImageToPng(byte[] imageData) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageData);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("Failed to read image data");
            }
            
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }
}
