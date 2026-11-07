package patchgen.gradle;

import com.nothome.delta.Delta;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Full target archive encoded as raw files and javaxdelta GDIFF v4 entries. */
public final class ArchivePatches {
    private ArchivePatches() {}
    static LinkedHashMap<Pattern,String> rules(Map<String,byte[]> archive) throws IOException {
        var raw = new LinkedHashMap<String,String>();
        for (String cfg : List.of("patch.cfg", "patch2.cfg", "patch3.cfg")) {
            if (!archive.containsKey(cfg)) continue;
            for (String line : new String(archive.get(cfg), StandardCharsets.UTF_8).split("\\R")) {
                line=line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
                int eq=line.indexOf('='); ArchiveIO.require(eq>0, "Invalid mapping: " + line);
                raw.put(line.substring(0,eq).trim(),line.substring(eq+1).trim());
            }
        }
        var result=new LinkedHashMap<Pattern,String>(); raw.forEach((key,value)->result.put(Pattern.compile(key),value)); return result;
    }

    static String baseName(String name, Map<Pattern,String> rules, String prefix) {
        for (var entry:rules.entrySet()) {
            var matcher=entry.getKey().matcher(name); if(!matcher.matches())continue;
            String target=entry.getValue(); if(target.equals("*"))return name;
            for(int i=1;i<=matcher.groupCount();i++)target=target.replace("$"+i,matcher.group(i));
            return target;
        }
        return !prefix.isEmpty() && name.startsWith(prefix)?name.substring(prefix.length()):name;
    }

    public static Map<String,byte[]> restore(Path baseJar, Path patchJar, String prefix) throws IOException {
        return restore(ArchiveIO.read(baseJar),ArchiveIO.read(patchJar),prefix);
    }

    static Map<String,byte[]> restore(Map<String,byte[]> base, Map<String,byte[]> patch, String prefix) throws IOException {
        var mappings=rules(patch); var result=new TreeMap<String,byte[]>();
        for(var entry:patch.entrySet()) {
            String name=entry.getKey();
            if(name.startsWith("patch/") && name.endsWith(".md5"))continue;
            if(name.startsWith("patch/") && name.endsWith(".xdelta")) {
                String target=name.substring(6,name.length()-7),source=baseName(target,mappings,prefix);
                ArchiveIO.require(base.containsKey(source),"Missing patch base: "+source);
                byte[] data=GDiff.apply(base.get(source),entry.getValue());
                byte[] expected=patch.get(name.substring(0,name.length()-7)+".md5");
                ArchiveIO.require(expected!=null && ArchiveIO.hash(data,"MD5").equals(new String(expected,StandardCharsets.US_ASCII).trim()),"Patch MD5 mismatch: "+target);
                ArchiveIO.require(result.put(target,data)==null,"Duplicate decoded target: "+target);
            } else ArchiveIO.require(result.put(name,entry.getValue())==null,"Duplicate decoded target: "+name);
        }
        return result;
    }

    public static void generate(Path baseJar, Map<String,byte[]> target, Path output, String prefix) throws IOException {
        var base=ArchiveIO.read(baseJar); var mappings=rules(target); var result=new TreeMap<String,byte[]>();
        var encoder=new Delta();
        for(var entry:target.entrySet()) {
            String name=entry.getKey(), source=baseName(name,mappings,prefix);
            ArchiveIO.require(!(name.startsWith("patch/")&&(name.endsWith(".xdelta")||name.endsWith(".md5"))),"Target uses reserved patch entry name: "+name);
            // A configured archive may only decode names covered by its mapping rules.
            // Keep other entries raw, including a directly readable installer manifest.
            boolean mapped=mappings.isEmpty() || mappings.keySet().stream().anyMatch(pattern->pattern.matcher(name).matches());
            if(mapped && base.containsKey(source)) {
                byte[] delta=encoder.compute(base.get(source),entry.getValue());
                // Verify with our independent decoder, before committing the output archive.
                ArchiveIO.require(Arrays.equals(entry.getValue(),GDiff.apply(base.get(source),delta)),"GDIFF roundtrip failed: "+name);
                put(result,"patch/"+name+".xdelta",delta);
                put(result,"patch/"+name+".md5",ArchiveIO.hash(entry.getValue(),"MD5").getBytes(StandardCharsets.US_ASCII));
            } else put(result,name,entry.getValue());
        }
        ArchiveIO.equal(target,restore(base,result,prefix));
        ArchiveIO.write(output,result);
    }

    static void put(Map<String,byte[]> entries,String name,byte[] data) throws IOException {
        ArchiveIO.require(entries.putIfAbsent(name,data)==null,"Patch entry collision: "+name);
    }

    static boolean matches(String name,List<String> patterns) {
        for(String pattern:patterns) {
            var regex=new StringBuilder();
            for(int i=0;i<pattern.length();i++) {
                char c=pattern.charAt(i);
                if(c=='*'&&i+1<pattern.length()&&pattern.charAt(i+1)=='*') {regex.append(".*");i++;}
                else if(c=='*')regex.append("[^/]*");
                else if(c=='?')regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            if(name.matches(regex.toString()))return true;
        }
        return false;
    }

    static Map<String,byte[]> target(Path base,Path modified,Path template,String prefix,List<String> unprefixed,List<String> additional) throws IOException {
        var incoming=ArchiveIO.read(modified); if(template==null)return incoming;
        var target=restore(base,template,prefix);
        for(var entry:incoming.entrySet()) {
            String name=entry.getKey();
            String key=name.endsWith(".class") && unprefixed.stream().noneMatch(name::startsWith)?prefix+name:name;
            if(target.containsKey(key)||matches(name,additional))target.put(key,entry.getValue());
        }
        return target;
    }
}
