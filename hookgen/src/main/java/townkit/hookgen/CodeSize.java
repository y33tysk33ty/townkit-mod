package townkit.hookgen;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Reads the bytecode length of every method straight from a class file (ASM doesn't expose it). */
final class CodeSize {
    private CodeSize() {
    }

    /** Method name + descriptor -> code length in bytes. */
    static Map<String, Integer> of(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        in.readInt(); // magic
        in.readUnsignedShort();
        in.readUnsignedShort();
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++; // longs and doubles take two slots
                }
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.skipNBytes(6); // access, this, super
        in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.skipNBytes(6);
            skipAttributes(in);
        }
        Map<String, Integer> out = new HashMap<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String desc = utf8[in.readUnsignedShort()];
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String attr = utf8[in.readUnsignedShort()];
                int len = in.readInt();
                if (attr.equals("Code")) {
                    in.skipNBytes(4); // max stack, max locals
                    int code = in.readInt();
                    out.put(name + desc, code);
                    in.skipNBytes(len - 8L);
                } else {
                    in.skipNBytes(len);
                }
            }
        }
        return out;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.skipNBytes(2);
            in.skipNBytes(in.readInt());
        }
    }
}
