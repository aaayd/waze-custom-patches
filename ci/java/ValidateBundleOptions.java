import app.morphe.patcher.patch.*;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Check real loader metadata and dependency closure for all 16 toggle combinations. */
public class ValidateBundleOptions {
    private static void visit(Patch<?> patch, Set<Patch<?>> closure) {
        if (closure.add(patch)) for (Patch<?> dependency : patch.getDependencies()) visit(dependency, closure);
    }
    public static void main(String[] args) throws Exception {
        File bundle = new File(args[0]);
        PatchLoader loader = args.length > 1
            ? new PatchLoader.Dex(Set.of(bundle), new File(args[1]))
            : new PatchLoader.Jar(Set.of(bundle));
        Set<String> expected = Set.of("Selectable map themes", "Detailed report icons at normal sizes", "Rank badge selector", "Unlock driver moods");
        List<Patch<?>> options = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Patch<?> patch : loader) if (patch.getName() != null) { options.add(patch); names.add(patch.getName()); }
        if (options.size() != 4 || !names.equals(expected)) throw new AssertionError("Unexpected options: " + names);
        for (int mask = 0; mask < 16; mask++) {
            Set<Patch<?>> closure = new HashSet<>();
            Set<String> selected = new HashSet<>();
            for (int i = 0; i < 4; i++) if ((mask & (1 << i)) != 0) {
                selected.add(options.get(i).getName()); visit(options.get(i), closure);
            }
            Set<String> actual = new HashSet<>();
            for (Patch<?> patch : closure) if (patch.getName() != null) actual.add(patch.getName());
            if (!selected.equals(actual)) throw new AssertionError("Unexpected automatic selection: " + selected + " -> " + actual);
        }
        try (ZipFile zip = new ZipFile(bundle)) {
            for (String path : List.of("extensions/theme-selector.dex", "extensions/badge-selector.dex", "classes.dex")) {
                if (zip.getEntry(path) == null || zip.getEntry(path).getSize() == 0) throw new AssertionError("Missing DEX: " + path);
            }
        }
        for (Patch<?> patch : options) {
            if (!(patch instanceof BytecodePatch)) continue;
            int streams = 0;
            for (var provider : ((BytecodePatch) patch).getExtensionStreamProviders$morphe_patcher()) {
                for (var input : provider.get()) try (InputStream stream = input.get()) {
                    byte[] magic = stream.readNBytes(4);
                    if (!Arrays.equals(magic, new byte[]{'d','e','x','\n'})) throw new AssertionError("Invalid extension: " + patch.getName());
                    streams++;
                }
            }
            int expectedStreams = patch.getName().equals("Unlock driver moods") ? 0 : 1;
            if (streams != expectedStreams) throw new AssertionError("Unexpected extension count for " + patch.getName() + ": " + streams);
        }
        System.out.println("PASS: exactly four independent options, all 16 selection combinations, and separately loadable theme/badge extensions on " + System.getProperty("java.vm.name"));
    }
}
