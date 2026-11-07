package patchgen.gradle;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GDiffTest {
    private byte[] patch(int opcode, byte[] operands) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.write(new byte[]{(byte)209,(byte)255,(byte)209,(byte)255,4});
        out.writeByte(opcode); out.write(operands); return bytes.toByteArray();
    }

    @Test public void allSevenCopyEncodings() throws Exception {
        byte[] source="0123456789".getBytes();
        for(int opcode=249;opcode<=255;opcode++) {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            if(opcode<=251)out.writeShort(2);else if(opcode==255)out.writeLong(2);else out.writeInt(2);
            if(opcode==249||opcode==252)out.writeByte(4);else if(opcode==250||opcode==253)out.writeShort(4);else out.writeInt(4);
            assertArrayEquals("2345".getBytes(),GDiff.apply(source,patch(opcode,bytes.toByteArray())));
        }
    }

    @Test public void extendedLiteralsZeroAndEof() throws Exception {
        for(int opcode:new int[]{3,247,248}) {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            if(opcode==247)out.writeShort(3);else if(opcode==248)out.writeInt(3);
            out.write("abc".getBytes());out.writeByte(0);out.writeByte(1);out.writeByte('d');
            assertArrayEquals("abcd".getBytes(),GDiff.apply(new byte[0],patch(opcode,bytes.toByteArray())));
        }
    }

    @Test public void rejectsMalformedAndOutOfRangePatches() throws Exception {
        assertThrows(IOException.class,()->GDiff.apply(new byte[0],new byte[5]));
        assertThrows(IOException.class,()->GDiff.apply(new byte[0],patch(3,new byte[]{1})));
        assertThrows(IOException.class,()->GDiff.apply(new byte[]{1},patch(249,new byte[]{0,1,1})));
        assertThrows(IOException.class,()->GDiff.apply(new byte[]{1},patch(255,new byte[]{-1,-1,-1,-1,-1,-1,-1,-1,0,0,0,1})));
        assertThrows(IOException.class,()->GDiff.apply(new byte[0],patch(248,new byte[]{127,-1,-1,-1})));
    }
}

