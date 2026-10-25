import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import app.morphe.patcher.patch.PatchException;
import local.wazemaps.DriverIconsPatchKt;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Exercise production mood discovery against real DEX and deliberately invalid bindings. */
public class ValidateMoodBinding {
    static final List<ClassDef> classes = new ArrayList<>();
    static void read(byte[] bytes) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("base.apk")) read(zip.readAllBytes());
                else if (entry.getName().matches("classes[0-9]*\\.dex"))
                    classes.addAll(DexBackedDexFile.fromInputStream(Opcodes.getDefault(),
                        new BufferedInputStream(new ByteArrayInputStream(zip.readAllBytes()))).getClasses());
            }
        }
    }
    static List<Instruction> code(Method method) {
        var result = new ArrayList<Instruction>();
        if (method.getImplementation() != null) method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    static Method copy(Method method, String name, List<Instruction> code) {
        var implementation = method.getImplementation();
        return new ImmutableMethod(method.getDefiningClass(), name, method.getParameters(), method.getReturnType(),
            method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(),
            new ImmutableMethodImplementation(implementation.getRegisterCount(), code, implementation.getTryBlocks(), implementation.getDebugItems()));
    }
    static void rejected(Method eligibility, List<Method> methods, String label) {
        try { DriverIconsPatchKt.babyMoodGate(eligibility, methods); }
        catch (Exception expected) {
            if (!(expected instanceof PatchException)) throw new AssertionError("Unexpected failure for " + label, expected);
            System.out.println("PASS rejected " + label); return;
        }
        throw new AssertionError("Accepted " + label);
    }
    static void returned(Method method, int value) {
        var code = code(method);
        if (code.size() < 2 || !(code.get(0) instanceof NarrowLiteralInstruction literal) || literal.getNarrowLiteral() != value ||
            !(code.get(0) instanceof OneRegisterInstruction register) || register.getRegisterA() != 0 ||
            code.get(1).getOpcode() != Opcode.RETURN || ((OneRegisterInstruction) code.get(1)).getRegisterA() != 0)
            throw new AssertionError("Expected patched return " + value + " in " + method);
    }
    public static void main(String[] args) throws Exception {
        read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0])));
        var candidates = new ArrayList<Method>();
        for (var type : classes) for (var method : type.getMethods()) {
            var strings = new HashSet<String>();
            for (var instruction : code(method)) if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference string)
                strings.add(string.getString());
            if (method.getReturnType().equals("Z") && method.getParameterTypes().toString().equals("[Landroid/content/Context;, Ljava/lang/String;]") &&
                strings.containsAll(List.of("wazer_dino", "wazer_8bit", "wazer_robot"))) candidates.add(method);
        }
        if (candidates.size() != 1) throw new AssertionError("Eligibility candidates: " + candidates.size());
        var eligibility = candidates.get(0);
        var methods = new ArrayList<Method>();
        for (var type : classes) if (type.getType().equals(eligibility.getDefiningClass())) type.getMethods().forEach(methods::add);
        var gate = DriverIconsPatchKt.babyMoodGate(eligibility, methods);
        var getter = methods.stream().filter(method -> method.equals(gate)).findFirst().orElseThrow();
        System.out.println("PASS real eligibility " + eligibility + " binds " + gate);
        if (args.length > 1 && args[1].equals("--patched")) {
            returned(eligibility, 1);
            returned(getter, 0);
            System.out.println("PASS patched mood eligibility and baby gate");
            return;
        }
        var code = code(eligibility);
        if (code.get(0).getOpcode() == Opcode.IGET_BOOLEAN) {
            var ambiguous = new ArrayList<>(methods);
            ambiguous.add(copy(getter, "ambiguousBabyGetter", code(getter)));
            rejected(eligibility, ambiguous, "duplicate field accessor");
            var missing = new ArrayList<>(methods);
            missing.remove(getter);
            rejected(eligibility, missing, "missing field accessor");
            var invalid = new ArrayList<>(code);
            var branch = (OffsetInstruction) code.get(1);
            invalid.set(1, new ImmutableInstruction21t(Opcode.IF_NEZ, ((OneRegisterInstruction) code.get(1)).getRegisterA(), branch.getCodeOffset()));
            rejected(copy(eligibility, eligibility.getName(), invalid), methods, "inverted eligibility branch");
            invalid = new ArrayList<>(code);
            var load = (TwoRegisterInstruction) code.get(0);
            invalid.set(0, new ImmutableInstruction22c(Opcode.IGET_BOOLEAN, load.getRegisterA(), load.getRegisterB(),
                new ImmutableFieldReference(eligibility.getDefiningClass(), "unrelatedFlag", "Z")));
            rejected(copy(eligibility, eligibility.getName(), invalid), methods, "unrelated eligibility field");
            invalid = new ArrayList<>(code);
            var call = (FiveRegisterInstruction) code.get(7);
            invalid.set(7, new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, call.getRegisterCount(), call.getRegisterC(), call.getRegisterD(),
                call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), new ImmutableMethodReference("Ljava/lang/String;", "startsWith", List.of("Ljava/lang/String;"), "Z")));
            rejected(copy(eligibility, eligibility.getName(), invalid), methods, "different eligibility predicate");
            invalid = new ArrayList<>(code);
            invalid.set(7, new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, call.getRegisterCount(), call.getRegisterD(), call.getRegisterC(),
                call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), ((ReferenceInstruction) code.get(7)).getReference()));
            rejected(copy(eligibility, eligibility.getName(), invalid), methods, "different predicate arguments");
            invalid = new ArrayList<>(code);
            int returnIndex = invalid.size() - 1;
            if (invalid.get(returnIndex).getOpcode() != Opcode.RETURN) throw new AssertionError("Expected final return fixture");
            int differentRegister = ((OneRegisterInstruction) invalid.get(returnIndex)).getRegisterA() == 0 ? 1 : 0;
            invalid.set(returnIndex, new ImmutableInstruction11x(Opcode.RETURN, differentRegister));
            rejected(copy(eligibility, eligibility.getName(), invalid), methods, "different returned predicate register");
            // Discovery remains stable after both early-return patches are present.
            var patched = new ArrayList<Instruction>(List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 1), new ImmutableInstruction11x(Opcode.RETURN, 0)));
            patched.addAll(code);
            var patchedGetter = new ArrayList<Instruction>(List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), new ImmutableInstruction11x(Opcode.RETURN, 0)));
            patchedGetter.addAll(code(getter));
            methods.remove(getter);
            methods.add(copy(getter, getter.getName(), patchedGetter));
            if (!DriverIconsPatchKt.babyMoodGate(copy(eligibility, eligibility.getName(), patched), methods).equals(gate))
                throw new AssertionError("Repeat application changed binding");
            System.out.println("PASS repeat inlined binding");
        } else {
            // Repeating the same getter call is not an ambiguous method identity.
            var calls = code.stream().filter(i -> i instanceof ReferenceInstruction ref && ref.getReference().equals(gate)).toList();
            if (calls.isEmpty()) throw new AssertionError("Direct baby call missing");
            var repeated = new ArrayList<>(code);
            repeated.add(calls.get(0));
            if (!DriverIconsPatchKt.babyMoodGate(copy(eligibility, eligibility.getName(), repeated), methods).equals(gate))
                throw new AssertionError("Repeated call changed binding");
            var ambiguous = new ArrayList<>(code);
            ambiguous.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(eligibility.getDefiningClass(), "ambiguousGate", List.of(), "Z")));
            rejected(copy(eligibility, eligibility.getName(), ambiguous), methods, "distinct direct gates");
            System.out.println("PASS repeated direct binding");
        }
    }
}
