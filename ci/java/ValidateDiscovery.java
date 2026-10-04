import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import local.wazemaps.ThemeColoursKt;
import local.wazemaps.WazeBindingsKt;
import java.util.*;

/** Semantic fixtures: compiler layout variations must not change which setting is patched. */
public class ValidateDiscovery {
    static final String FIELD = "CONFIG_VALUE_MOODS_BETA_ENABLED";
    static final String CONFIG = "Lfixture/BooleanConfig;";
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void rejected(Runnable action, String message) {
        try { action.run(); }
        catch (Exception expected) {
            if (expected instanceof app.morphe.patcher.patch.PatchException) return;
            throw new AssertionError("Unexpected fixture failure", expected);
        }
        throw new AssertionError(message);
    }
    static ImmutableMethod gate(List<Instruction> instructions) {
        return new ImmutableMethod("Lfixture/MoodScreen;", "loadRows", List.of(), "V", 1, Set.of(), Set.of(),
            new ImmutableMethodImplementation(12, instructions, List.of(), List.of()));
    }
    static List<Instruction> prefix(boolean overwrite) {
        List<Instruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction21c(Opcode.SGET_OBJECT, 2,
            new ImmutableFieldReference("Lcom/waze/config/ConfigValues;", FIELD, CONFIG)));
        code.add(new ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 7, 2));
        for (int i = 0; i < 30; i++) code.add(new ImmutableInstruction10x(Opcode.NOP));
        if (overwrite) code.add(new ImmutableInstruction11n(Opcode.CONST_4, 7, 0));
        return code;
    }
    static void gates() {
        for (boolean boxed : new boolean[] {false, true}) for (boolean range : new boolean[] {false, true}) {
            var code = prefix(false);
            var ref = boxed ? new ImmutableMethodReference(CONFIG, "read", List.of(), "Ljava/lang/Boolean;") :
                new ImmutableMethodReference("Lcom/waze/ConfigManager;", "readBool", List.of(CONFIG), "Z");
            code.add(range ? new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, boxed ? 7 : 6, boxed ? 1 : 2, ref) :
                new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, boxed ? 1 : 2, boxed ? 7 : 6, 7, 0, 0, 0, ref));
            if (boxed) {
                code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 8));
                code.add(new ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 9, 8));
                code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 9, 0, 0, 0, 0,
                    new ImmutableMethodReference("Ljava/lang/Boolean;", "booleanValue", List.of(), "Z")));
            }
            int result = code.size();
            code.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 4));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            var found = WazeBindingsKt.booleanConfigResult(gate(code), FIELD);
            require(found.getFirst() == result && found.getSecond() == 4, "Wrong boolean result for boxed=" + boxed + " range=" + range);
        }
        var unrelated = prefix(true);
        unrelated.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 6, 7, 0, 0, 0,
            new ImmutableMethodReference("Lcom/waze/ConfigManager;", "readBool", List.of(CONFIG), "Z")));
        unrelated.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 4));
        unrelated.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        rejected(() -> WazeBindingsKt.booleanConfigResult(gate(unrelated), FIELD), "Accepted overwritten config register");
        var branch = prefix(false);
        branch.add(new ImmutableInstruction10t(Opcode.GOTO, 1));
        branch.addAll(unrelated.subList(33, unrelated.size()));
        rejected(() -> WazeBindingsKt.booleanConfigResult(gate(branch), FIELD), "Traced through unproven control flow");
    }
    static void palettes() {
        String source = "local Palette = {map_background = rgb ( 0xFFFFFF ), labels=rgb(0xAAAAAA)}\nColors = {General = {map_background=Palette.map_background}}\n";
        var palette = Map.of("map_background", "000000", "labels", "BBBBBB", "ad_labels_color", "CCCCCC");
        var colors = Map.of("General.map_background", "000000", "AdPinBusinessName.labels_color", "CCCCCC");
        String result = ThemeColoursKt.recolorSkin(source, palette, colors);
        require(result.contains("map_background = rgb(0x000000)") && result.contains("labels=rgb(0xBBBBBB)"), "Compact Lua palette was not recoloured");
        require(result.contains("Colors.General.map_background = rgb(0x000000)") && !result.contains("Colors.AdPinBusinessName"), "Optional table handling changed output");
        rejected(() -> ThemeColoursKt.recolorSkin(source.replace("map_background = rgb ( 0xFFFFFF )", "map_background = other()"), palette, colors), "Accepted changed core colour expression");
        rejected(() -> ThemeColoursKt.recolorSkin(source.replace("labels=rgb(0xAAAAAA)", "labels=rgb(0xAAAAAA), labels=rgb(0xAAAAAA)"), palette, colors), "Accepted duplicate palette key");
        rejected(() -> ThemeColoursKt.recolorSkin(source.replace("General = {map_background=Palette.map_background}", "General = invalid"), palette, colors), "Treated malformed table as optional");
    }
    public static void main(String[] args) {
        gates(); palettes();
        System.out.println("PASS semantic discovery: primitive/boxed configs, normal/range invokes, moved registers, 30-instruction separation, optional and compact palettes; rejects overwritten values, branches, duplicate colours and malformed tables");
    }
}
