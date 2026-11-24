import java.io.File;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

class FindAudio {
    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (var cls : container.getEntry(entry).getDexFile().getClasses()) {
                for (var method : cls.getMethods()) {
                    var implementation = method.getImplementation();
                    if (implementation == null) continue;
                    for (var instruction : implementation.getInstructions()) {
                        if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference string &&
                            (string.getString().equals("audio/eac3-joc") || string.getString().equals("audio/eac3"))) {
                            System.out.printf("%s %s %s flags=%x %s%n", entry, cls.getType(), method, method.getAccessFlags(), string.getString());
                        }
                    }
                }
            }
        }
    }
}
