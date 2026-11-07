package patchgen.gradle;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

final class ArchiveIO {
    static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }

    static String hash(byte[] bytes, String algorithm) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static Map<String,byte[]> read(Path file) throws IOException {
        var result = new TreeMap<String,byte[]>();
        try (var zip = new ZipFile(file.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                validateName(entry.getName());
                require(!result.containsKey(entry.getName()), "Duplicate ZIP entry: " + entry.getName());
                try (var in = zip.getInputStream(entry)) { result.put(entry.getName(), in.readAllBytes()); }
            }
        }
        return result;
    }

    static void validateName(String name) throws IOException {
        require(!name.isBlank() && !name.contains("\\") && !name.startsWith("/") && !name.matches("^[A-Za-z]:.*")
            && Arrays.stream(name.split("/")).noneMatch(".."::equals), "Unsafe archive path: " + name);
    }

    static void write(Path file, Map<String,byte[]> entries) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".part");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (var e : new TreeMap<>(entries).entrySet()) {
                    validateName(e.getKey()); var ze = new ZipEntry(e.getKey()); ze.setTime(0);
                    zip.putNextEntry(ze); zip.write(e.getValue()); zip.closeEntry();
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    static void equal(Map<String,byte[]> expected, Map<String,byte[]> actual) throws IOException {
        require(expected.keySet().equals(actual.keySet()), "Archive entries differ");
        for (var entry : expected.entrySet()) require(Arrays.equals(entry.getValue(), actual.get(entry.getKey())), "Byte mismatch: " + entry.getKey());
    }
}
