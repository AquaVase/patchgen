package patchgen.gradle;

import java.io.*;
import java.util.Arrays;

/** javaxdelta GDIFF v4 decoder; zero is a no-op and EOF is permitted. */
final class GDiff {
    static byte[] apply(byte[] source, byte[] patch) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(patch));
        if (!Arrays.equals(input.readNBytes(5), new byte[]{(byte)209, (byte)255, (byte)209, (byte)255, 4}))
            throw new IOException("Expected javaxdelta GDIFF v4 header");
        var output = new ByteArrayOutputStream();
        for (int opcode; (opcode = input.read()) != -1;) {
            if (opcode == 0) continue;
            if (opcode <= 248) {
                int length = opcode <= 246 ? opcode : opcode == 247 ? input.readUnsignedShort() : input.readInt();
                if (length < 0 || length > input.available()) throw new EOFException("Invalid or truncated literal length");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new EOFException("Truncated literal");
                output.write(bytes);
            } else {
                long offset = opcode <= 251 ? input.readUnsignedShort() : opcode == 255 ? input.readLong() : input.readInt();
                int length = switch (opcode) {
                    case 249, 252 -> input.readUnsignedByte();
                    case 250, 253 -> input.readUnsignedShort();
                    default -> input.readInt();
                };
                if (offset < 0 || length < 0 || offset > source.length || length > source.length - offset)
                    throw new IOException("GDIFF copy outside source");
                output.write(source, (int)offset, length);
            }
        }
        return output.toByteArray();
    }
}

