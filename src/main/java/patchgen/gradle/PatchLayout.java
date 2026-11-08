package patchgen.gradle;

import java.io.IOException;
import java.util.Arrays;

/** Paths inside the patch archive, independent of bytecode naming. */
record PatchLayout(String root,String notch,String srg) {
    static PatchLayout of(String root,String notch,String srg) throws IOException {
        var layout=new PatchLayout(directory(root),directory(notch),directory(srg));
        if(!layout.srg.isEmpty())
            ArchiveIO.require(!layout.notch.isEmpty()&&!layout.notch.startsWith(layout.srg)&&!layout.srg.startsWith(layout.notch),
                "notchPrefix and srgPrefix must be non-empty, distinct, non-overlapping directories when SRG patches are enabled");
        return layout;
    }
    static String directory(String value) throws IOException {
        if(value.isEmpty())return "";
        String path=value.endsWith("/")?value.substring(0,value.length()-1):value;
        ArchiveIO.validateName(path);
        ArchiveIO.require(!path.contains(":")&&Arrays.stream(path.split("/",-1)).noneMatch(part->part.isEmpty()||part.equals(".")),
            "Invalid archive directory: "+value);
        return path+"/";
    }
    boolean isSrg(String name) { return !srg.isEmpty()&&name.startsWith(srg); }
    String prefix(String name) { return isSrg(name)?srg:notch; }
    boolean reserved(String name) { return name.startsWith(root)&&(name.endsWith(".xdelta")||name.endsWith(".md5")); }
}
