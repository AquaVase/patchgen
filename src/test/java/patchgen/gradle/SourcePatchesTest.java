package patchgen.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SourcePatchesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    Path base,sources,patches,restored;

    @Before public void fixture() throws Exception {
        Path root=temp.getRoot().toPath();base=root.resolve("base.jar");sources=root.resolve("src");
        patches=root.resolve("patches");restored=root.resolve("restored");
    }

    private void baseline(Map<String,String> entries) throws Exception {
        var bytes=new TreeMap<String,byte[]>();entries.forEach((name,text)->bytes.put(name,text.getBytes(StandardCharsets.UTF_8)));
        ArchiveIO.write(base,bytes);
    }
    private void source(String name,String content) throws Exception {
        Path file=sources.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,content);
    }

    @Test public void restoresOnlyOverridesAndAdditionsAndPreservesOtherFiles() throws Exception {
        baseline(Map.of("net/minecraft/Changed.java","class Changed {\n    int value = 1;\n}\n",
            "net/minecraft/Untouched.java","class Untouched {}\n","net/minecraft/Same.java","class Same {}\n"));
        source("net/minecraft/Changed.java","class Changed {\r\n    int value = 2; // 中文\r\n}\r\n");
        source("net/minecraft/Same.java","class Same {}\n");
        source("demo/Added.java","class Added {}");source("demo/Empty.java","");
        source("ignore.txt","not Java");
        SourcePatches.generate(base,sources,patches);
        String patch=Files.readString(patches.resolve("net/minecraft/Changed.java.patch"));
        assertTrue(patch.startsWith("--- a/net/minecraft/Changed.java\n+++ b/net/minecraft/Changed.java\n"));
        assertTrue(patch.contains("-    int value = 1;\n+    int value = 2; // 中文\n"));
        assertFalse(Files.exists(patches.resolve("net/minecraft/Same.java.patch")));
        Files.createDirectories(restored);Files.writeString(restored.resolve("Keep.java"),"keep");
        SourcePatches.restore(base,patches,restored);
        assertEquals("class Changed {\n    int value = 2; // 中文\n}\n",Files.readString(restored.resolve("net/minecraft/Changed.java")));
        assertEquals("class Added {}",Files.readString(restored.resolve("demo/Added.java")));
        assertEquals("",Files.readString(restored.resolve("demo/Empty.java")));
        assertEquals("keep",Files.readString(restored.resolve("Keep.java")));
        assertFalse(Files.exists(restored.resolve("net/minecraft/Untouched.java")));
        assertFalse(Files.exists(restored.resolve("net/minecraft/Same.java")));
    }

    @Test public void newlineEmptyFileAndMultipleHunkChangesRoundTrip() throws Exception {
        List<String> samples=List.of("","\n","\n\n","a","a\n","a\nb","a\nb\n","a\n\nb\n",
            "中文\nαβ\n","zero\none\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n");
        for(String original:samples)for(String target:samples) {
            baseline(Map.of("Sample.java",original));source("Sample.java",target);
            SourcePatches.generate(base,sources,patches);
            if(original.equals(target))assertFalse(Files.exists(patches.resolve("Sample.java.patch")));
            else assertEquals(target,SourcePatches.apply("Sample.java",original.getBytes(StandardCharsets.UTF_8),Files.readString(patches.resolve("Sample.java.patch"))));
        }
        String original="a\n"+"context\n".repeat(12)+"z\n";
        String target="b\n"+"context\n".repeat(12)+"y\n";
        baseline(Map.of("Sample.java",original));source("Sample.java",target);SourcePatches.generate(base,sources,patches);
        assertEquals(target,SourcePatches.apply("Sample.java",original.getBytes(StandardCharsets.UTF_8),Files.readString(patches.resolve("Sample.java.patch"))));
    }

    @Test public void removesStalePatchesWithoutRemovingReadmeOrTreatingMissingOverridesAsDeletions() throws Exception {
        baseline(Map.of("Example.java","before\n"));source("Example.java","after\n");
        SourcePatches.generate(base,sources,patches);Files.writeString(patches.resolve("README.md"),"keep");
        Files.delete(sources.resolve("Example.java"));SourcePatches.generate(base,sources,patches);
        assertFalse(Files.exists(patches.resolve("Example.java.patch")));
        assertEquals("keep",Files.readString(patches.resolve("README.md")));
        SourcePatches.restore(base,patches,restored);assertFalse(Files.exists(restored.resolve("Example.java")));
    }

    @Test public void conflictsDoNotPartiallyOverwriteSources() throws Exception {
        baseline(Map.of("A.java","original\n","Z.java","original\n"));source("A.java","patched\n");source("Z.java","patched\n");
        SourcePatches.generate(base,sources,patches);
        baseline(Map.of("A.java","original\n","Z.java","different version\n"));
        Files.createDirectories(restored);Files.writeString(restored.resolve("A.java"),"user edits");
        IOException failure=assertThrows(IOException.class,()->SourcePatches.restore(base,patches,restored));
        assertTrue(failure.getMessage().contains("Z.java.patch"));
        assertEquals("user edits",Files.readString(restored.resolve("A.java")));
        assertFalse(Files.exists(restored.resolve("Z.java")));
    }

    @Test public void rejectsUnsafeHeadersMalformedHunksAndOverlappingDirectories() throws Exception {
        baseline(Map.of());Files.createDirectories(patches);
        Path patch=patches.resolve("Added.java.patch");
        for(String content:List.of("--- /dev/null\n+++ b/../escape.java\n@@ -0,0 +1 @@\n+bad\n",
            "--- /dev/null\n+++ b/Added.java\n@@ -0,0 +1,2 @@\n+bad\n",
            "--- /dev/null\n+++ b/Added.java\n@@ -0,0 +1 @@\n+bad\ntrailing junk\n")) {
            Files.writeString(patch,content);
            assertThrows(IOException.class,()->SourcePatches.restore(base,patches,restored));
        }
        assertFalse(Files.exists(restored));
        assertThrows(IOException.class,()->SourcePatches.generate(base,sources,sources.resolve("patches")));
    }
}
