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
        return restore(base,Map.of(),patch,PatchLayout.of("patch/",prefix,""));
    }

    static Map<String,byte[]> restore(Map<String,byte[]> base,Map<String,byte[]> srgBase,Map<String,byte[]> patch,PatchLayout layout) throws IOException {
        var mappings=rules(patch); var result=new TreeMap<String,byte[]>();
        for(var entry:patch.entrySet()) {
            String name=entry.getKey();
            if(name.startsWith(layout.root()) && name.endsWith(".md5"))continue;
            if(name.startsWith(layout.root()) && name.endsWith(".xdelta")) {
                String target=name.substring(layout.root().length(),name.length()-7),source=baseName(target,mappings,layout.prefix(target));
                var selected=layout.isSrg(target)?srgBase:base;
                ArchiveIO.require(selected.containsKey(source),"Missing "+(layout.isSrg(target)?"SRG":"Notch")+" patch base: "+source);
                byte[] data=GDiff.apply(selected.get(source),entry.getValue());
                byte[] expected=patch.get(name.substring(0,name.length()-7)+".md5");
                ArchiveIO.require(expected!=null && ArchiveIO.hash(data,"MD5").equals(new String(expected,StandardCharsets.US_ASCII).trim()),"Patch MD5 mismatch: "+target);
                ArchiveIO.require(result.put(target,data)==null,"Duplicate decoded target: "+target);
            } else ArchiveIO.require(result.put(name,entry.getValue())==null,"Duplicate decoded target: "+name);
        }
        return result;
    }

    public static void generate(Path baseJar, Map<String,byte[]> target, Path output, String prefix) throws IOException {
        generate(ArchiveIO.read(baseJar),Map.of(),target,output,PatchLayout.of("patch/",prefix,""));
    }

    static void generate(Map<String,byte[]> base,Map<String,byte[]> srgBase,Map<String,byte[]> target,Path output,PatchLayout layout) throws IOException {
        var mappings=rules(target); var result=new TreeMap<String,byte[]>();
        var encoder=new Delta();
        for(var entry:target.entrySet()) {
            String name=entry.getKey(), source=baseName(name,mappings,layout.prefix(name));
            var selected=layout.isSrg(name)?srgBase:base;
            ArchiveIO.require(!layout.reserved(name),"Target uses reserved patch entry name: "+name);
            // A configured archive may only decode names covered by its mapping rules.
            // Keep other entries raw, including a directly readable installer manifest.
            boolean mapped=mappings.isEmpty() || mappings.keySet().stream().anyMatch(pattern->pattern.matcher(name).matches());
            if(mapped && selected.containsKey(source)) {
                byte[] delta=encoder.compute(selected.get(source),entry.getValue());
                // Verify with our independent decoder, before committing the output archive.
                ArchiveIO.require(Arrays.equals(entry.getValue(),GDiff.apply(selected.get(source),delta)),"GDIFF roundtrip failed: "+name);
                put(result,layout.root()+name+".xdelta",delta);
                put(result,layout.root()+name+".md5",ArchiveIO.hash(entry.getValue(),"MD5").getBytes(StandardCharsets.US_ASCII));
            } else put(result,name,entry.getValue());
        }
        ArchiveIO.equal(target,restore(base,srgBase,result,layout));
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
        return target(ArchiveIO.read(base),Map.of(),ArchiveIO.read(modified),Map.of(),template==null?null:ArchiveIO.read(template),
            PatchLayout.of("patch/",prefix,""),unprefixed,additional);
    }

    static Map<String,byte[]> target(Map<String,byte[]> base,Map<String,byte[]> srgBase,Map<String,byte[]> notchIncoming,
            Map<String,byte[]> srgIncoming,Map<String,byte[]> template,PatchLayout layout,List<String> unprefixed,List<String> additional) throws IOException {
        var target=template==null?new TreeMap<String,byte[]>():restore(base,srgBase,template,layout);
        overlay(target,notchIncoming,layout.notch(),false,template!=null,unprefixed,additional);
        overlay(target,srgIncoming,layout.srg(),true,template!=null,unprefixed,additional);
        return target;
    }

    private static void overlay(Map<String,byte[]> target,Map<String,byte[]> incoming,String prefix,boolean srg,boolean template,
            List<String> unprefixed,List<String> additional) throws IOException {
        for(var entry:incoming.entrySet()) {
            String name=entry.getKey();
            boolean shared=!name.endsWith(".class")||unprefixed.stream().anyMatch(name::startsWith);
            // Shared resources and explicitly unprefixed classes are supplied by the Notch target once.
            if(srg&&shared)continue;
            String key=shared?name:prefix+name;
            ArchiveIO.validateName(key);
            if(!template||target.containsKey(key)||matches(name,additional))target.put(key,entry.getValue());
        }
    }
}
