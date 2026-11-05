package ai.nc.varcospaces;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The same ready catalog drives restoration, media browsing, search and playback. */
final class SceneCatalog {
    interface PackagedFile { boolean exists(String path); }
    static final class Item {
        final String id, title, titleEn, subtitle, subtitleEn;
        final String[] labels, labelsEn, files;
        final float[] defaults;
        final boolean available;
        Item(String id, String title, String titleEn, String subtitle, String subtitleEn,
             boolean available, String[] files, String[] labels, String[] labelsEn, float[] defaults) {
            this.id = id; this.title = title; this.titleEn = titleEn;
            this.subtitle = subtitle; this.subtitleEn = subtitleEn; this.available = available;
            this.files = files.clone(); this.labels = labels.clone(); this.labelsEn = labelsEn.clone();
            this.defaults = defaults.clone();
        }
        boolean ready() {
            if (!available || id == null || !id.matches("[a-z][a-z0-9_-]{0,63}") || files.length != 3) return false;
            for (String file : files) if (file == null || !file.startsWith("content/") || !SafetyPolicy.isPackagedPath(file)) return false;
            return defaults.length == 3;
        }
        String title(boolean english) { return english && !titleEn.isEmpty() ? titleEn : !title.isEmpty() ? title : id; }
        String subtitle(boolean english) {
            String value = english ? subtitleEn : subtitle;
            if (!value.isEmpty()) return value;
            String[] values = english ? labelsEn : labels;
            List<String> nonempty = new ArrayList<>();
            for (String label : values) if (label != null && !label.isEmpty()) nonempty.add(label);
            return String.join(" · ", nonempty);
        }
        boolean matches(String query) {
            if (matchesText(id, query) || matchesText(title, query) || matchesText(titleEn, query)) return true;
            for (String label : labels) if (matchesText(label, query)) return true;
            for (String label : labelsEn) if (matchesText(label, query)) return true;
            return false;
        }
    }
    private final Map<String, Item> items = new LinkedHashMap<>();
    SceneCatalog(List<Item> candidates) {
        for (Item item : candidates) if (item != null && item.ready() && !items.containsKey(item.id)) items.put(item.id, item);
    }
    static SceneCatalog read(JSONObject catalog, PackagedFile files) {
        List<Item> candidates = new ArrayList<>();
        JSONArray scenes = catalog == null ? null : catalog.optJSONArray("scenes");
        for (int i = 0; scenes != null && i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i); if (scene == null) continue;
            JSONArray layers = scene.optJSONArray("layers");
            boolean ready = scene.optBoolean("available", false) && layers != null && layers.length() == 3;
            String[] paths = new String[3], labels = new String[3], labelsEn = new String[3];
            float[] defaults = new float[3];
            for (int j = 0; j < 3; j++) {
                JSONObject layer = layers == null ? null : layers.optJSONObject(j);
                paths[j] = layer == null ? "" : layer.optString("file");
                labels[j] = layer == null ? "" : layer.optString("label", layer.optString("id"));
                labelsEn[j] = layer == null ? "" : layer.optString("labelEn", layer.optString("id"));
                defaults[j] = layer == null ? 0 : SafetyPolicy.volume(layer.optDouble("volume", .5));
                ready &= layer != null && layer.optBoolean("available", false)
                    && paths[j].startsWith("content/") && SafetyPolicy.isPackagedPath(paths[j])
                    && (files == null || files.exists(paths[j]));
            }
            candidates.add(new Item(scene.optString("id"), scene.optString("title"), scene.optString("titleEn"),
                scene.optString("subtitle"), scene.optString("subtitleEn"), ready, paths, labels, labelsEn, defaults));
        }
        return new SceneCatalog(candidates);
    }
    List<Item> all() { return Collections.unmodifiableList(new ArrayList<>(items.values())); }
    Item get(String id) { return items.get(id); }
    String restore(String saved) { return items.containsKey(saved) ? saved : items.isEmpty() ? "" : items.keySet().iterator().next(); }
    List<Item> search(String query) {
        String text = normalize(query);
        if (text.isEmpty() || text.equals("park lounge") || text.equals("파크 라운지")
            || text.equals("varco spaces") || text.equals("바르코 스페이스")) return all();
        List<Item> matches = new ArrayList<>();
        for (Item item : items.values()) if (item.matches(text)) matches.add(item);
        return matches;
    }
    String searchSelection(String query, String current) {
        List<Item> matches = search(query);
        // A generic app request resumes the selected space; a specific title is deterministic.
        for (Item item : matches) if (item.id.equals(current)) return current;
        return matches.isEmpty() ? null : matches.get(0).id;
    }
    private static String normalize(String text) { return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    private static boolean matchesText(String text, String query) { return text != null && !text.isEmpty() && normalize(text).contains(query); }
}
