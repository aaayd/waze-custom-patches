import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.reandroid.arsc.chunk.xml.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** Inspect actual patched APKs, including independent selections and the full build. */
public class ValidatePatchSelection {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static ResXmlElement named(ResXmlElement parent, String tag, String name) {
        if (parent == null) return null;
        var elements = parent.getElements(tag);
        while (elements.hasNext()) {
            var element = (ResXmlElement) elements.next();
            if (name.equals(AndroidManifestBlock.getAndroidNameValue(element))) return element;
        }
        return null;
    }
    static Method method(Map<String, ClassDef> classes, String type, String name, String parameter) {
        for (var method : classes.get(type).getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().size() == 1 &&
                    parameter.contentEquals(method.getParameterTypes().get(0))) return method;
        throw new AssertionError("Missing method " + type + name);
    }
    static boolean calls(Instruction instruction, String type, String name) {
        if (!(instruction instanceof ReferenceInstruction)) return false;
        var reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof MethodReference && ((MethodReference) reference).getDefiningClass().equals(type)
            && ((MethodReference) reference).getName().equals(name);
    }
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        boolean themes = Boolean.parseBoolean(args[1]), icons = Boolean.parseBoolean(args[2]), auto = Boolean.parseBoolean(args[3]);
        int originalCode = Integer.parseInt(args[4]);
        String prefix = "Llocal/wazemaps/themes/";
        Map<String, ClassDef> classes = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (var entry : dex.getDexEntryNames()) for (var type : dex.getEntry(entry).getDexFile().getClasses())
            require(classes.put(type.getType(), type) == null, "Duplicate class " + type.getType());
        require(classes.containsKey(prefix + "ThemeSelector;") == themes, "Theme class selection");
        require(classes.containsKey(prefix + "IconPack;") == icons, "Icon class selection");
        for (String name : List.of("CompanionInstaller", "CompanionSetupActivity", "CompanionApkProvider", "AndroidAutoSettings"))
            require(classes.containsKey(prefix + name + ";") == auto, "Android Auto class selection: " + name);
        var launch = method(classes, "Lcom/waze/MainActivity;", "onCreate", "Landroid/os/Bundle;");
        int attach = 0, index = 0;
        for (var instruction : launch.getImplementation().getInstructions()) {
            if (calls(instruction, prefix + "CompanionInstaller;", "attach")) {
                require(index == 0 && instruction instanceof RegisterRangeInstruction, "Startup hook must use Activity at entry");
                var range = (RegisterRangeInstruction) instruction;
                require(range.getRegisterCount() == 1 && range.getStartRegister() == launch.getImplementation().getRegisterCount() - 2,
                    "Startup hook does not use p0");
                attach++;
            }
            index++;
        }
        require(attach == (auto ? 1 : 0), "Unexpected startup hook count");
        var render = method(classes, "Lcom/waze/settings/tree/f;", "k", "Lcom/waze/settings/de;");
        int exits = 0, themeRows = 0, iconRows = 0, autoRows = 0;
        for (var instruction : render.getImplementation().getInstructions()) {
            if (instruction.getOpcode() == Opcode.RETURN_OBJECT) exits++;
            if (calls(instruction, prefix + "ThemeSelector;", "decorate")) themeRows++;
            if (calls(instruction, prefix + "IconPack;", "decorate")) iconRows++;
            if (calls(instruction, prefix + "AndroidAutoSettings;", "decorate")) autoRows++;
        }
        require(exits > 0 && themeRows == (themes ? exits : 0) && iconRows == (icons ? exits : 0) && autoRows == (auto ? exits : 0), "Settings hook selection mismatch");
        try (var zip = new ZipFile(apk)) {
            var asset = zip.getEntry("assets/morphe/installer/waze-aa-installer.apk");
            require((asset != null) == auto, "Installer asset selection mismatch");
            if (auto) {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(asset).readAllBytes());
                require(HexFormat.of().formatHex(hash).equals("e5d442454418efd4ff438b3ed3f6bfa5a3aee78f52616625cb25ce0ec8855b48"), "Installer checksum mismatch");
            }
            require((zip.getEntry("assets/morphe/iconpacks/paths.txt") != null) == icons, "Icon pack asset selection mismatch");
            require((zip.getEntry("assets/morphe/themes/oled/skin_values.day.lua") != null) == themes, "Theme asset selection mismatch");
            var manifest = AndroidManifestBlock.load(zip.getInputStream(zip.getEntry("AndroidManifest.xml")));
            require(manifest.getVersionCode() == Math.max(1030749, originalCode + 17), "Manifest version edits did not compose");
            require(manifest.getUsesPermissions().contains("android.permission.REQUEST_INSTALL_PACKAGES") == auto, "Install permission selection mismatch");
            for (String[] component : new String[][] {{"activity", "CompanionSetupActivity"}, {"provider", "CompanionApkProvider"}}) {
                var element = named(manifest.getApplicationElement(), component[0], "local.wazemaps.themes." + component[1]);
                require((element != null) == auto, "Manifest component selection: " + component[1]);
                if (auto) require(element.searchAttributeByResourceId(0x01010010).getData() == 0, "Companion component is exported");
            }
            var query = named(manifest.getManifestElement().getElement("queries"), "package", "local.waze.aainstaller");
            require((query != null) == auto, "Companion query selection mismatch");
        }
        System.out.println("PASS actual APK: themes=" + themes + ", icons=" + icons + ", Android Auto=" + auto + "; isolated classes, assets, permissions, components, startup and settings hooks");
    }
}
