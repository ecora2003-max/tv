import java.io.File;
import java.util.zip.ZipFile;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;

class BuildUi {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        var oldDex = DexFileFactory.loadDexEntry(apk, args[1], true, Opcodes.getDefault()).getDexFile();
        var replacement = DexFileFactory.loadDexFile(new File(args[2]), Opcodes.getDefault())
                .getClasses().iterator().next();
        var pool = new DexPool(oldDex.getOpcodes());
        int replacements = 0;
        for (var cls : oldDex.getClasses()) {
            if (cls.getType().equals(replacement.getType())) {
                pool.internClass(replacement);
                replacements++;
            } else {
                pool.internClass(cls);
            }
        }
        if (replacements != 1) throw new IllegalStateException("Expected one main activity class");
        var dexOutput = new FileDataStore(new File(args[3]));
        try {
            pool.writeTo(dexOutput);
        } finally {
            dexOutput.close();
        }

        var manifest = new ResXmlDocument();
        try (var zip = new ZipFile(apk); var stream = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
            manifest.readBytes(stream);
        }
        int activities = 0;
        for (Object appItem : manifest.getDocumentElement().listElements("application")) {
            for (Object activityItem : ((ResXmlElement) appItem).listElements("activity")) {
                var activity = (ResXmlElement) activityItem;
                var name = activity.searchAttributeByResourceId(0x01010003);
                if (name == null || !name.getValueAsString().equals("com.apple.android.music.common.MainActivity")) continue;
                activity.getOrCreateAndroidAttribute("screenOrientation", 0x0101001e).setTypeAndData(ValueType.DEC, 0);
                activities++;
            }
        }
        if (activities != 1) throw new IllegalStateException("Expected one main activity in the manifest");
        manifest.refresh();
        manifest.writeBytes(new File(args[4]));
    }
}
