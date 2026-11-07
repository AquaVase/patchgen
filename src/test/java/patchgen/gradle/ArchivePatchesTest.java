package patchgen.gradle;

import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ArchivePatchesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void genericArchiveRoundtripSupportsModifiedAddedRemovedAndUnchangedEntries() throws Exception {
        Path dir=temp.newFolder().toPath(),base=dir.resolve("base.jar"),out=dir.resolve("patches.jar");
        ArchiveIO.write(base,Map.of("changed.txt","original".getBytes(),"same.bin",new byte[]{1,2,3},"removed.txt",new byte[1]));
        var target=Map.of("changed.txt","modified".getBytes(),"same.bin",new byte[]{1,2,3},"added.bin",new byte[]{9});
        ArchivePatches.generate(base,target,out,"");
        ArchiveIO.equal(target,ArchivePatches.restore(base,out,""));
        assertTrue(ArchiveIO.read(out).containsKey("patch/changed.txt.xdelta"));
        assertTrue(ArchiveIO.read(out).containsKey("added.bin"));
    }

    @Test public void anonymousClassMappingPreservesLiteralDollarsAndConfigPrecedence() throws Exception {
        var rules=ArchivePatches.rules(Map.of("patch.cfg","notch/(.*)=$1\nsame=first\n".getBytes(),
            "patch2.cfg","srg/Foo\\$1.class=Foo$1.class\nsame=second\n".getBytes()));
        assertEquals("Foo$1.class",ArchivePatches.baseName("srg/Foo$1.class",rules,""));
        assertEquals("Foo$1.class",ArchivePatches.baseName("notch/Foo$1.class",rules,""));
        assertEquals("second",ArchivePatches.baseName("same",rules,""));
    }

    @Test public void configuredArchiveKeepsUnmappedManifestDirectlyReadable() throws Exception {
        Path dir=temp.newFolder().toPath(),base=dir.resolve("base.jar"),out=dir.resolve("patch.jar");
        String manifest="META-INF/MANIFEST.MF";
        ArchiveIO.write(base,Map.of(manifest,"vanilla".getBytes(),"Game.class",new byte[]{1}));
        var target=Map.of(manifest,"Main-Class: example.Installer\r\n\r\n".getBytes(),
            "patch.cfg","notch/(.*)=$1".getBytes(),"notch/Game.class",new byte[]{2});
        ArchivePatches.generate(base,target,out,"notch/");
        var encoded=ArchiveIO.read(out);
        assertArrayEquals(target.get(manifest),encoded.get(manifest));
        assertFalse(encoded.containsKey("patch/"+manifest+".xdelta"));
        assertTrue(encoded.containsKey("patch/notch/Game.class.xdelta"));
        ArchiveIO.equal(target,ArchivePatches.restore(base,out,"notch/"));
    }

    @Test public void templateOverlayRetainsOtherNamespacesAndOnlyIncludesExplicitNewEntries() throws Exception {
        Path dir=temp.newFolder().toPath(),base=dir.resolve("base.jar"),template=dir.resolve("template.jar"),modified=dir.resolve("modified.jar");
        ArchiveIO.write(base,Map.of("Game.class","vanilla".getBytes()));
        var expanded=Map.of("notch/Game.class","mod".getBytes(),"srg/Game.class","forge".getBytes(),"patch.cfg","(notch|srg)/(.*)=$2".getBytes());
        ArchivePatches.generate(base,expanded,template,"");
        ArchiveIO.write(modified,Map.of("Game.class","changed".getBytes(),"Unrelated.class",new byte[0],"custom/New.class",new byte[1]));
        var target=ArchivePatches.target(base,modified,template,"notch/",List.of(),List.of("custom/**"));
        assertArrayEquals("changed".getBytes(),target.get("notch/Game.class"));
        assertArrayEquals("forge".getBytes(),target.get("srg/Game.class"));
        assertTrue(target.containsKey("notch/custom/New.class"));assertFalse(target.containsKey("notch/Unrelated.class"));
    }

    @Test public void rejectsMd5MismatchAndRetainsPreviousOutputOnFailure() throws Exception {
        Path dir=temp.newFolder().toPath(),base=dir.resolve("base.jar"),patch=dir.resolve("patch.jar");
        ArchiveIO.write(base,Map.of("file",new byte[]{1}));ArchivePatches.generate(base,Map.of("file",new byte[]{2}),patch,"");
        var entries=ArchiveIO.read(patch);entries.put("patch/file.md5","bad".getBytes());ArchiveIO.write(patch,entries);
        assertThrows(java.io.IOException.class,()->ArchivePatches.restore(base,patch,""));
        byte[] before=Files.readAllBytes(patch);
        assertThrows(java.io.IOException.class,()->ArchivePatches.generate(base,Map.of("patch/reserved.xdelta",new byte[0]),patch,""));
        assertArrayEquals(before,Files.readAllBytes(patch));
    }

    @Test public void rejectsZipTraversalAndHandlesLiteralGlobCharacters() throws Exception {
        for(String name:List.of("../evil","/evil","C:/evil","..\\evil"))assertThrows(java.io.IOException.class,()->ArchiveIO.validateName(name));
        assertTrue(ArchivePatches.matches("custom/a+b$.class",List.of("custom/a+b$.class")));
        assertFalse(ArchivePatches.matches("custom/ab.class",List.of("custom/a+b$.class")));
    }
}
