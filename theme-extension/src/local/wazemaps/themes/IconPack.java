package local.wazemaps.themes;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Map texture copies and Java asset reads use the same selected pack. */
public final class IconPack {
    private static final String TAG = "MorpheIcons";
    private static final String PREFS = "morphe_map_themes";
    private static final String KEY = "icon_pack";
    private static final String ROOT = "morphe/iconpacks/";
    private static volatile Context application;
    private static volatile Set<String> paths;
    private static volatile boolean google;

    private static boolean selected(Context context) {
        return "google_maps".equals(context.getSharedPreferences(PREFS, 0).getString(KEY, "google_maps"));
    }

    public static String summary(Context context) {
        return selected(context) ? "Google Maps" : "Waze original";
    }

    private static synchronized void initialize(Context context) throws Exception {
        if (paths != null) return;
        Set<String> entries = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(ROOT + "paths.txt"), "UTF-8"))) {
            String path;
            while ((path = reader.readLine()) != null) {
                if (path.isEmpty() || path.contains("..") || path.startsWith("/") || path.contains("\\") || !path.endsWith(".png"))
                    throw new IOException("Invalid icon pack path");
                if (!entries.add(path)) throw new IOException("Duplicate icon pack path");
            }
        }
        if (entries.isEmpty()) throw new IOException("Empty icon pack");
        application = context.getApplicationContext();
        google = selected(context);
        paths = Collections.unmodifiableSet(entries);
    }

    /** Hooked before ResourcesNativeManager reads an original skin asset. */
    public static InputStream open(String name) {
        try {
            if (paths == null) {
                Context context = (Context) Class.forName("k.z").getMethod("j", Class.class).invoke(null, Application.class);
                initialize(context);
            }
            if (!google || name == null || !paths.contains(name)) return null;
            return application.getAssets().open(ROOT + "google_maps/" + name);
        } catch (Exception error) {
            Log.e(TAG, "Could not open icon override", error);
            return null; // Waze's unchanged original loader handles every fallback.
        }
    }

    public static void prepare(Context context) throws Exception {
        initialize(context);
        install(context, selected(context));
        google = selected(context);
        Log.i(TAG, "Icon pack prepared: " + summary(context) + " (" + paths.size() + " assets)");
    }

    private static void write(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create icon directory");
        File staged = new File(parent, file.getName() + ".morphe-tmp");
        try {
            Files.write(staged.toPath(), data);
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged.toPath()); }
    }

    /** Read and validate the whole pack before changing any working texture. */
    private static synchronized void install(Context context, boolean useGoogle) throws Exception {
        initialize(context);
        File root = new File(context.getFilesDir().getParentFile(), "waze/skins/default");
        List<File> targets = new ArrayList<>();
        List<byte[]> before = new ArrayList<>(), after = new ArrayList<>();
        for (String path : paths) {
            String asset = (useGoogle ? ROOT + "google_maps/" : "res/skins/default/") + path;
            byte[] bytes;
            try (InputStream input = context.getAssets().open(asset); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                bytes = output.toByteArray();
            }
            if (bytes.length < 24 || bytes[0] != (byte)137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71)
                throw new IOException("Invalid PNG: " + path);
            File file = new File(root, path);
            targets.add(file);
            before.add(file.isFile() ? Files.readAllBytes(file.toPath()) : null);
            after.add(bytes);
        }
        int written = 0;
        try {
            for (; written < targets.size(); written++) {
                if (!Arrays.equals(before.get(written), after.get(written))) write(targets.get(written), after.get(written));
            }
        } catch (Exception error) {
            for (int i = 0; i < written; i++) {
                try {
                    if (before.get(i) == null) Files.deleteIfExists(targets.get(i).toPath());
                    else write(targets.get(i), before.get(i));
                } catch (Exception restore) { error.addSuppressed(restore); }
            }
            throw error;
        }
    }

    public static void show(Context context) {
        boolean[] draft = {selected(context)};
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("Icon pack")
                .setSingleChoiceItems(new String[]{"Waze original", "Google Maps"}, draft[0] ? 1 : 0,
                        (choice, which) -> draft[0] = which == 1)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply & restart", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            apply(context, draft[0], dialog);
        }));
        dialog.show();
    }

    private static void apply(Context context, boolean choice, AlertDialog dialog) {
        Context app = context.getApplicationContext();
        Runnable task = () -> {
            boolean saved = false;
            try {
                boolean old = selected(app);
                install(app, choice);
                if (!app.getSharedPreferences(PREFS, 0).edit().putString(KEY, choice ? "google_maps" : "waze").commit()) {
                    install(app, old);
                    throw new IOException("Could not save icon pack");
                }
                google = choice;
                saved = true;
                Log.i(TAG, "Icon pack saved: " + summary(app));
            } catch (Exception error) { Log.e(TAG, "Could not apply icon pack", error); }
            final boolean didSave = saved;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (didSave) { dialog.dismiss(); ThemeSelector.restart(context); }
                else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    Toast.makeText(context, "Could not apply icon pack. Please try again.", Toast.LENGTH_LONG).show();
                }
            });
        };
        try {
            Object queued = Class.forName("com.waze.NativeManager").getMethod("Post", Runnable.class).invoke(null, task);
            if (Boolean.FALSE.equals(queued)) throw new IllegalStateException("Map engine unavailable");
        } catch (Exception error) {
            Log.e(TAG, "Could not queue icon update", error);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            Toast.makeText(context, "Map engine is not ready. Please try again.", Toast.LENGTH_LONG).show();
        }
    }
}
