import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.Adler32;
import java.util.zip.ZipFile;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

class PatchAtmos {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        String entry = args[2];
        var dex = DexFileFactory.loadDexEntry(apk, entry, true, Opcodes.getDefault()).getDexFile();
        int replacement = dex.getStringSection().indexOf("audio/eac3");
        int original = dex.getStringSection().indexOf("audio/eac3-joc");
        if (replacement < 0 || original < 0) throw new IllegalStateException("Missing expected MIME constants");
        int location = -1;
        for (var cls : dex.getClasses()) {
            if (!cls.getType().equals("Lcom/apple/android/music/util/AudioDeviceCapabilities;")) continue;
            for (var method : cls.getMethods()) {
                if (!method.getName().equals("isDolbyAtmosAvailable") || !method.getReturnType().equals("Z")) continue;
                for (var instruction : method.getImplementation().getInstructions()) {
                    if (instruction.getOpcode() != Opcode.CONST_STRING && instruction.getOpcode() != Opcode.CONST_STRING_JUMBO) continue;
                    var value = (StringReference)((ReferenceInstruction)instruction).getReference();
                    if (!value.getString().equals("audio/eac3-joc")) continue;
                    if (location != -1) throw new IllegalStateException("Ambiguous match");
                    location = ((DexBackedInstruction)instruction).instructionStart;
                }
            }
        }
        if (location == -1) throw new IllegalStateException("No match in isDolbyAtmosAvailable");
        byte[] bytes;
        try (var zip = new ZipFile(apk)) {
            bytes = zip.getInputStream(zip.getEntry(entry)).readAllBytes();
        }
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = Byte.toUnsignedInt(data.get(location));
        if (opcode == 0x1a && replacement <= 0xffff && Short.toUnsignedInt(data.getShort(location + 2)) == original) {
            data.putShort(location + 2, (short)replacement);
        } else if (opcode == 0x1b && data.getInt(location + 2) == original) {
            data.putInt(location + 2, replacement);
        } else {
            throw new IllegalStateException("Unexpected instruction encoding");
        }
        var sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(bytes, 32, bytes.length - 32);
        System.arraycopy(sha1.digest(), 0, bytes, 12, 20);
        var adler32 = new Adler32();
        adler32.update(bytes, 12, bytes.length - 12);
        data.putInt(8, (int)adler32.getValue());
        Files.write(Path.of(args[1]), bytes);
        System.out.printf("%s offset %d: changed a single MIME constant in isDolbyAtmosAvailable%n", entry, location);
    }
}
