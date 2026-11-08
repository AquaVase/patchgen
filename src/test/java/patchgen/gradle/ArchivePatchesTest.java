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

    @Test public void customPatchRootRoutesNamespacesToIndependentBasesAndVerifiesMd5() throws Exception {
        Path output=temp.newFile("dual.jar").toPath();
        var base=Map.of("a.class","notch-base".getBytes());
        var srgBase=Map.of("net/minecraft/Game.class","srg-base".getBytes());
        var target=Map.of("notch/a.class","notch-modified".getBytes(),
            "srg/net/minecraft/Game.class","srg-modified".getBytes(),"assets/new.txt","new".getBytes());
        var layout=PatchLayout.of("custom/binary","notch","srg");
        ArchivePatches.generate(base,srgBase,target,output,layout);
        var encoded=ArchiveIO.read(output);
        assertTrue(encoded.containsKey("custom/binary/notch/a.class.xdelta"));
        assertTrue(encoded.containsKey("custom/binary/srg/net/minecraft/Game.class.xdelta"));
        assertTrue(encoded.containsKey("custom/binary/srg/net/minecraft/Game.class.md5"));
        assertFalse(encoded.containsKey("patch/notch/a.class.xdelta"));
        ArchiveIO.equal(target,ArchivePatches.restore(base,srgBase,encoded,layout));
        assertThrows(java.io.IOException.class,()->ArchivePatches.restore(base,Map.of(),encoded,layout));
        encoded.put("custom/binary/srg/net/minecraft/Game.class.md5","invalid".getBytes());
        assertThrows(java.io.IOException.class,()->ArchivePatches.restore(base,srgBase,encoded,layout));
    }

    @Test public void dualOverlayPrefixesClassesAndPreservesTemplateNamespacesAndSharedResources() throws Exception {
        var layout=PatchLayout.of("delta/","notch/","srg/");
        var notch=Map.of("a.class","new-notch".getBytes(),"assets/test.txt","resource".getBytes(),"shared/Util.class",new byte[]{8});
        var srg=Map.of("Game.class","new-srg".getBytes(),"assets/test.txt","srg-resource".getBytes(),"shared/Util.class",new byte[]{9});
        var target=ArchivePatches.target(Map.of(),Map.of(),notch,srg,null,layout,List.of("shared/"),List.of());
        assertTrue(target.containsKey("notch/a.class"));assertTrue(target.containsKey("srg/Game.class"));
        assertFalse(target.containsKey("srg/assets/test.txt"));assertArrayEquals("resource".getBytes(),target.get("assets/test.txt"));
        assertArrayEquals(new byte[]{8},target.get("shared/Util.class"));
        var template=Map.of("notch/a.class","old-notch".getBytes(),"srg/Game.class","old-srg".getBytes(),"third/Keep.class",new byte[]{1});
        var overlaid=ArchivePatches.target(Map.of(),Map.of(),notch,srg,template,layout,List.of("shared/"),List.of("assets/**"));
        assertArrayEquals("new-notch".getBytes(),overlaid.get("notch/a.class"));
        assertArrayEquals("new-srg".getBytes(),overlaid.get("srg/Game.class"));assertTrue(overlaid.containsKey("third/Keep.class"));
        assertFalse(overlaid.containsKey("shared/Util.class"));
    }

    @Test public void patchRootCanBeArchiveRootAndInvalidLayoutsFailBeforeWriting() throws Exception {
        Path output=temp.newFile("root.jar").toPath();
        var base=Map.of("file",new byte[]{1});var target=Map.of("file",new byte[]{2});
        var layout=PatchLayout.of("","","");ArchivePatches.generate(base,Map.of(),target,output,layout);
        assertTrue(ArchiveIO.read(output).containsKey("file.xdelta"));
        ArchiveIO.equal(target,ArchivePatches.restore(base,Map.of(),ArchiveIO.read(output),layout));
        for(String bad:List.of("../bad","/absolute","C:/bad","a//b","a/./b","a\\b"))
            assertThrows(java.io.IOException.class,()->PatchLayout.of(bad,"",""));
        for(String[] prefixes:List.of(new String[]{"","srg"},new String[]{"same","same"},new String[]{"a","a/b"}))
            assertThrows(java.io.IOException.class,()->PatchLayout.of("patch",prefixes[0],prefixes[1]));
        byte[] before=Files.readAllBytes(output);
        assertThrows(java.io.IOException.class,()->ArchivePatches.generate(base,Map.of(),Map.of("file.md5",new byte[0]),output,layout));
        assertArrayEquals(before,Files.readAllBytes(output));
    }
}
