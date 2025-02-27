/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.integration.domain.mixed;

import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @author Tomaz Cerar
 */
class OldVersionCopier {

    private static final String OLD_VERSIONS_DIR = "jboss.test.mixed.domain.dir";

    private static final Logger log = Logger.getLogger(OldVersionCopier.class.getName());

    private final Version.AsVersion version;
    private final Path oldVersionsBaseDir;
    private final Path targetOldVersions = Paths.get("target/old-versions/");


    private OldVersionCopier(Version.AsVersion version, Path oldVersionsBaseDir) {
        this.version = version;
        this.oldVersionsBaseDir = oldVersionsBaseDir;
    }

    static Path getOldVersionDir(Version.AsVersion version) {

        OldVersionCopier copier = new OldVersionCopier(version, obtainOldVersionsDir());
        copier.cleanUnneededInstances();
        Path result = copier.getExpandedPath();
        if (Files.exists(result) && Files.isDirectory(result) && Files.exists(result.resolve("jboss-modules.jar"))) { //verify expanded version is proper
            return result;
        }
        return copier.expandAsInstance(version);
    }

    private void cleanUnneededInstances() {
        // WFLY-12021 if we're using version X assume we don't need the expanded zip
        // for any other version any more. So delete it to reduce the peak disk footprint
        // of a testsuite execution.
        for (Version.AsVersion asVersion : Version.AsVersion.values()) {
            if (asVersion != version) {
                Path versionRoot = targetOldVersions.resolve(asVersion.getFullVersionName());
                if (versionRoot.toFile().exists() && versionRoot.toFile().isDirectory()) {
                    log.infof("Cleaning legacy installation at %s", versionRoot);
                    try (Stream<Path> stream = Files.walk(versionRoot)) {
                        //noinspection ResultOfMethodCallIgnored
                        stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                    } catch (IOException e) {
                        log.errorf(e, "Failed cleaning legacy installation at %s", versionRoot);
                    }
                }
            }
        }
    }

    private static Path obtainOldVersionsDir() {
        String error = "System property '" + OLD_VERSIONS_DIR + "' must be set to a directory containing old versions";
        String oldVersionsDir = System.getProperty(OLD_VERSIONS_DIR);
        if (oldVersionsDir == null) {
            throw new RuntimeException(error);
        }
        Path file = Paths.get(oldVersionsDir);
        if (Files.notExists(file) || !Files.isDirectory(file)) {
            throw new RuntimeException(error);
        }
        return file;
    }

    private Path getExpandedPath() {
        return targetOldVersions.resolve(version.getFullVersionName());
    }

    private Path expandAsInstance(Version.AsVersion version) {
        createIfNotExists(targetOldVersions);


        final String path = resolveZipFileName(version);
        Path file = oldVersionsBaseDir.resolve(path);
        if (Files.notExists(file)) {
            throw new RuntimeException("Old version not found in " + file.toAbsolutePath().toString());
        }
        try {
            return expandAsInstance(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path expandAsInstance(final Path file) throws Exception {
        Path versionDir = getExpandedPath();
        createIfNotExists(versionDir);
        unzip(file, versionDir);
        return versionDir;
    }

    private static boolean shouldSkip(Path path) {
        String entryName = path.toString();
        if (entryName.endsWith("/docs/") || entryName.endsWith("/bundles/")) {
            return true;
        } else if (entryName.endsWith("/bin/")) {
            return true;
        } else if (entryName.endsWith("/eap/dir/")) { //console files
            return true;
        } else if (entryName.contains("/welcome-content/") && !entryName.endsWith("/welcome-content/")) {
            //Create the directory but don't include any files
            return true;
        }
        return false;
    }

    private void createIfNotExists(Path file) {
        if (Files.notExists(file)) {
            try {
                Files.createDirectories(file);
            } catch (IOException e) {
                throw new RuntimeException("Could not create " + targetOldVersions, e);
            }
        }
    }

    private static FileSystem createZipFileSystem(Path zipFilename) throws IOException {
        // convert the filename to a URI
        final URI uri = URI.create("jar:file:" + zipFilename.toUri().getPath());

        final Map<String, String> env = new HashMap<>();
        return FileSystems.newFileSystem(uri, env);
    }

    /**
     * Unzips the specified zip file to the specified destination directory.
     * Replaces any files in the destination, if they already exist.
     */
    private void unzip(Path zipFilename, Path destDir) throws IOException {
        if (Files.notExists(destDir)) {
            Files.createDirectories(destDir);
        }

        try (FileSystem zipFileSystem = createZipFileSystem(zipFilename)) {
            final Path root = zipFileSystem.getPath("/");

            Files.walkFileTree(verifyZipContents(root), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final Path destFile = (Paths.get(destDir.toString(), file.subpath(1, file.getNameCount()).toString()));  //we skip first folder
                    /*if (!destFile.toString().endsWith(".jar")&&!destFile.toString().endsWith(".xml")) {
                        System.out.printf("Extracting file %s to %s\n", file, destFile);
                    }*/
                    Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.getNameCount() == 1) {
                        return FileVisitResult.CONTINUE;
                    }
                    final Path dirToCreate = Paths.get(destDir.toString(), dir.subpath(1, dir.getNameCount()).toString());
                    if (shouldSkip(dir)) {
                        //System.out.println("skipping, " + dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.notExists(dirToCreate)) {
                        Files.createDirectory(dirToCreate);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static Path verifyZipContents(Path root) throws IOException {
        boolean read = false;
        Path result = root;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path c : stream) {
                if (!read) {
                    result = c;
                    read = true;
                } else {
                    throw new RuntimeException("Zip contains more than one directory, something is wrong!");
                }

            }
        }
        return result;
    }

    private static String resolveZipFileName(final Version.AsVersion requested) {
        final String value = System.getProperty("override.mixed.domain." + requested.getVersion());
        if (value != null) {
            return value;
        }
        return requested.getZipFileName();
    }

}
