package patchgen.gradle;

import com.cloudbees.diff.Diff;
import com.cloudbees.diff.Difference;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Sparse Java overrides against the mapped MCP archive, stored as one unified diff per file. */
final class SourcePatches {
    private static final String SUFFIX=".java.patch";
    private static final String NO_NEWLINE="\\ No newline at end of file";
    private static final Pattern HUNK=Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?");

    static void generate(Path baseArchive,Path sources,Path patches) throws IOException {
        generate(baseArchive,sources,patches,false);
    }

    static void generate(Path baseArchive,Path sources,Path patches,boolean ignoreIndentation) throws IOException {
        separate(sources,patches);
        var base=ArchiveIO.read(baseArchive);
        var generated=new TreeMap<String,String>();
        for(Path source:files(sources,".java")) {
            String name=relative(sources,source);
            String target=text(Files.readAllBytes(source));
            String original=base.containsKey(name)?text(base.get(name)):"";
            if(ignoreIndentation)target=alignIndentation(original,target);
            if(base.containsKey(name)&&original.equals(target))continue;
            // Include line terminators in the comparison so newline-only changes are not lost.
            var diff=Diff.diff(lines(original),lines(target),false);
            String patch=normalize(diff.toUnifiedDiff(base.containsKey(name)?"a/"+name:"/dev/null","b/"+name,
                new StringReader(original),new StringReader(target),3));
            ArchiveIO.require(target.equals(apply(name,base.get(name),patch)),"Source patch round-trip failed: "+name);
            generated.put(name+".patch",patch);
        }
        // Validate every destination before changing any existing patches.
        for(String name:generated.keySet())safe(patches,name);
        var stale=files(patches,SUFFIX);
        for(Path file:stale)safe(patches,relative(patches,file));
        Files.createDirectories(patches);
        for(var entry:generated.entrySet())write(safe(patches,entry.getKey()),entry.getValue());
        for(Path file:stale)if(!generated.containsKey(relative(patches,file)))Files.delete(file);
    }

    /** Preserve baseline text on matched lines; changed/new lines retain the user's text. */
    static String alignIndentation(String original,String target) throws IOException {
        // Text-block indentation can change the value of a Java string. Keep such files exact.
        // Conservatively include triple quotes in comments rather than risk altering a literal.
        if(original.contains("\"\"\"")||target.contains("\"\"\""))return target;
        var base=lines(original);var result=new ArrayList<>(lines(target));
        var baseKeys=base.stream().map(SourcePatches::withoutIndentation).toList();
        var targetKeys=result.stream().map(SourcePatches::withoutIndentation).toList();
        int oldCursor=0,newCursor=0;
        for(var change:Diff.diff(baseKeys,targetKeys,false)) {
            int oldStart=change.getType()==Difference.ADD?change.getFirstStart():change.getFirstStart()-1;
            int newStart=change.getType()==Difference.DELETE?change.getSecondStart():change.getSecondStart()-1;
            while(oldCursor<oldStart&&newCursor<newStart) {
                ArchiveIO.require(baseKeys.get(oldCursor).equals(targetKeys.get(newCursor)),"Invalid indentation alignment");
                result.set(newCursor++,base.get(oldCursor++));
            }
            oldCursor=change.getType()==Difference.ADD?oldStart:change.getFirstEnd();
            newCursor=change.getType()==Difference.DELETE?newStart:change.getSecondEnd();
        }
        while(oldCursor<base.size()&&newCursor<result.size()) {
            ArchiveIO.require(baseKeys.get(oldCursor).equals(targetKeys.get(newCursor)),"Invalid trailing indentation alignment");
            result.set(newCursor++,base.get(oldCursor++));
        }
        return String.join("",result);
    }

    private static String withoutIndentation(String line) {
        int index=0;
        while(index<line.length()&&(line.charAt(index)==' '||line.charAt(index)=='\t'))index++;
        return line.substring(index);
    }

    static void restore(Path baseArchive,Path patches,Path sources) throws IOException {
        separate(sources,patches);
        var base=ArchiveIO.read(baseArchive);
        var restored=new TreeMap<Path,String>();
        // Apply all patches in memory first: a conflict must not partially overwrite user sources.
        for(Path patch:files(patches,SUFFIX)) {
            String patchName=relative(patches,patch);
            String name=patchName.substring(0,patchName.length()-".patch".length());
            Path destination=safe(sources,name);
            try { restored.put(destination,apply(name,base.get(name),text(Files.readAllBytes(patch)))); }
            catch(IOException|RuntimeException e) { throw new IOException("Cannot restore source patch "+patchName+": "+e.getMessage(),e); }
        }
        Files.createDirectories(sources);
        for(var entry:restored.entrySet())write(entry.getKey(),entry.getValue());
    }

    /** Strict application: verify paths, ranges, removed lines and context instead of guessing a fuzzy match. */
    static String apply(String name,byte[] original,String patch) throws IOException {
        var input=lines(original==null?"":text(original));
        var patchLines=normalize(patch).lines().toList();
        ArchiveIO.require(patchLines.size()>=2,"Missing unified diff headers");
        boolean addition=patchLines.get(0).equals("--- /dev/null");
        ArchiveIO.require(patchLines.get(0).equals("--- a/"+name)||addition,"Unexpected base path in patch for "+name);
        ArchiveIO.require(patchLines.get(1).equals("+++ b/"+name),"Unexpected target path in patch for "+name);
        ArchiveIO.require(addition==(original==null),addition?"Added source already exists in MCP: "+name:"Source missing from MCP: "+name);
        var output=new ArrayList<String>();
        int cursor=0,hunks=0;
        for(int index=2;index<patchLines.size();) {
            var range=HUNK.matcher(patchLines.get(index++));
            ArchiveIO.require(range.matches(),"Invalid unified diff hunk");
            int oldStart=Integer.parseInt(range.group(1)),oldCount=count(range.group(2));
            int newStart=Integer.parseInt(range.group(3)),newCount=count(range.group(4));
            int start=oldCount==0?oldStart:oldStart-1;
            ArchiveIO.require(start>=cursor&&start<=input.size(),"Hunk base range is outside the source");
            output.addAll(input.subList(cursor,start));cursor=start;
            ArchiveIO.require((newCount==0?newStart:newStart-1)==output.size(),"Inconsistent target hunk range");
            int removed=0,added=0;
            while(index<patchLines.size()&&!patchLines.get(index).startsWith("@@ ")) {
                String line=patchLines.get(index++);
                ArchiveIO.require(!line.isEmpty()&&" +-".indexOf(line.charAt(0))>=0,"Invalid hunk line");
                char kind=line.charAt(0);String content=line.substring(1);
                boolean noNewline=index<patchLines.size()&&patchLines.get(index).equals(NO_NEWLINE);
                if(noNewline)index++;
                if(kind!='+') {
                    ArchiveIO.require(cursor<input.size()&&stripNewline(input.get(cursor)).equals(content),"Context mismatch at line "+(cursor+1));
                    ArchiveIO.require(!noNewline||!input.get(cursor).endsWith("\n"),"Unexpected newline at line "+(cursor+1));
                    if(kind==' ')output.add(input.get(cursor));
                    cursor++;removed++;
                }
                if(kind=='+')output.add(content+(noNewline?"":"\n"));
                if(kind!='-')added++;
            }
            ArchiveIO.require(removed==oldCount&&added==newCount,"Hunk line counts do not match its range");
            hunks++;
        }
        ArchiveIO.require(hunks>0||addition,"Patch contains no changes");
        output.addAll(input.subList(cursor,input.size()));
        // An unterminated line is only valid at the end of a file.
        for(int i=0;i+1<output.size();i++)ArchiveIO.require(output.get(i).endsWith("\n"),"Unterminated line before end of source");
        return String.join("",output);
    }

    private static int count(String value) { return value==null?1:Integer.parseInt(value); }
    private static String stripNewline(String line) { return line.endsWith("\n")?line.substring(0,line.length()-1):line; }
    private static String normalize(String text) { return text.replace("\r\n","\n").replace('\r','\n'); }
    private static String text(byte[] bytes) throws IOException {
        return normalize(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }
    private static List<String> lines(String text) {
        if(text.isEmpty())return List.of();
        var result=new ArrayList<>(Arrays.asList(text.split("(?<=\n)",-1)));
        if(result.getLast().isEmpty())result.removeLast();
        return result;
    }
    private static List<Path> files(Path root,String suffix) throws IOException {
        if(!Files.exists(root))return List.of();
        ArchiveIO.require(Files.isDirectory(root),"Not a directory: "+root);
        try(var paths=Files.walk(root)) {
            var result=paths.filter(path->!Files.isDirectory(path)&&path.toString().endsWith(suffix)).sorted().toList();
            for(Path file:result)safe(root,relative(root,file));
            return result;
        }
    }
    private static String relative(Path root,Path file) { return root.relativize(file).toString().replace('\\','/'); }
    private static Path safe(Path root,String name) throws IOException {
        ArchiveIO.validateName(name);
        ArchiveIO.require(!name.contains(":")&&!name.contains("\n")&&!name.contains("\r")&&!name.contains("\t")
            &&Arrays.stream(name.split("/",-1)).noneMatch(part->part.isEmpty()||part.equals(".")||!part.equals(part.strip())),"Unsafe source path: "+name);
        Path destination=root.resolve(name).toAbsolutePath().normalize();
        Path absoluteRoot=root.toAbsolutePath().normalize();
        ArchiveIO.require(destination.startsWith(absoluteRoot),"Source path escapes directory: "+name);
        for(Path path=destination;path!=null;path=path.getParent())
            ArchiveIO.require(!Files.isSymbolicLink(path),"Symbolic links are not supported: "+path);
        return destination;
    }
    private static void separate(Path sources,Path patches) throws IOException {
        Path source=sources.toFile().getCanonicalFile().toPath(),patch=patches.toFile().getCanonicalFile().toPath();
        ArchiveIO.require(!source.startsWith(patch)&&!patch.startsWith(source),"Source and source-patch directories must not overlap");
    }
    private static void write(Path destination,String text) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temp=Files.createTempFile(destination.getParent(),".patchgen-",".tmp");
        try {
            Files.writeString(temp,text,StandardCharsets.UTF_8);
            Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
