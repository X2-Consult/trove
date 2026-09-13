package org.booklore.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MountInfoTest {

    // Trimmed from a real host: root on ext4, an NFS library, an SMB share with a space in its
    // mount point, and a local FUSE pool (mergerfs) that must not count as network storage.
    private static final List<MountInfo.Mount> HOST = MountInfo.parse(List.of(
            "29 1 253:0 / / rw,relatime shared:1 - ext4 /dev/mapper/vg-root rw",
            "36 29 0:52 / /mnt/nas rw,relatime shared:30 - nfs4 nas.local:/volume1/books rw,vers=4.2",
            "37 29 0:53 / /mnt/smb\\040share rw,relatime shared:31 - cifs //nas/books rw,vers=3.1.1",
            "38 29 0:54 / /mnt/pool rw,relatime shared:32 - fuse.mergerfs pool rw",
            "39 36 0:55 / /mnt/nas/local-overlay rw,relatime shared:33 - tmpfs tmpfs rw"));

    @Test
    void findsTheDeepestMountHoldingAPath() {
        assertThat(MountInfo.find(Path.of("/mnt/nas/Author/Book.epub"), HOST))
                .hasValueSatisfying(m -> {
                    assertThat(m.fsType()).isEqualTo("nfs4");
                    assertThat(m.source()).isEqualTo("nas.local:/volume1/books");
                    assertThat(m.isNetwork()).isTrue();
                });
        assertThat(MountInfo.find(Path.of("/mnt/nas/local-overlay/x"), HOST)).hasValueSatisfying(m -> assertThat(m.fsType()).isEqualTo("tmpfs"));
        assertThat(MountInfo.find(Path.of("/srv/trove/library"), HOST)).hasValueSatisfying(m -> assertThat(m.isNetwork()).isFalse());
    }

    @Test
    void decodesEscapedSpacesInMountPoints() {
        assertThat(MountInfo.find(Path.of("/mnt/smb share/Comics/issue.cbz"), HOST))
                .hasValueSatisfying(m -> {
                    assertThat(m.mountPoint()).isEqualTo("/mnt/smb share");
                    assertThat(m.isNetwork()).isTrue();
                });
    }

    @Test
    void localFuseFilesystemsAreNotNetwork() {
        assertThat(MountInfo.find(Path.of("/mnt/pool/books"), HOST)).hasValueSatisfying(m -> assertThat(m.isNetwork()).isFalse());
    }

    // The mount points in these tests mustn't exist on the machine running them: find() follows
    // symlinks, so a real /books (as on a Docker host) would change the answer.
    @Test
    void theLaterOfTwoMountsOnOnePointWins() {
        List<MountInfo.Mount> stacked = MountInfo.parse(List.of(
                "29 1 253:0 / / rw - ext4 /dev/sda1 rw",
                "40 29 0:60 / /trove-test-books rw - ext4 /dev/sdb1 rw",
                "41 40 0:61 / /trove-test-books rw - nfs nas:/books rw"));

        assertThat(MountInfo.find(Path.of("/trove-test-books/a.epub"), stacked)).hasValueSatisfying(m -> assertThat(m.fsType()).isEqualTo("nfs"));
    }

    @Test
    void dockerBindMountsReportTheShareBehindThem() {
        // A NAS folder bind-mounted into a container shows up with the share's own type.
        List<MountInfo.Mount> container = MountInfo.parse(List.of(
                "600 500 0:90 / / rw,relatime - overlay overlay rw,lowerdir=/var/lib/docker/...",
                "610 600 0:52 /volume1/books /trove-test-books rw,relatime - nfs4 nas.local:/volume1 rw"));

        assertThat(MountInfo.find(Path.of("/trove-test-books"), container)).hasValueSatisfying(m -> assertThat(m.isNetwork()).isTrue());
    }

    @Test
    void malformedLinesAreSkipped_andNoMountsMeansUnknown() {
        assertThat(MountInfo.parse(List.of("garbage", "1 2 3"))).isEmpty();
        assertThat(MountInfo.find(Path.of("/anything"), List.of())).isEmpty();
    }
}
