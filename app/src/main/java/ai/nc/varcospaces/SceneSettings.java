package ai.nc.varcospaces;

import java.util.LinkedHashMap;
import java.util.Map;

/** Device-local per-space mix. Reading settings never requests playback. */
final class SceneSettings {
    private final SceneCatalog catalog;
    private final Map<String, float[]> mixes = new LinkedHashMap<>();
    private String selected;
    private boolean hasSelected;
    SceneSettings(SceneCatalog catalog, Map<String, ?> stored) {
        this.catalog = catalog;
        Object saved = stored.get("sceneId");
        String savedId = saved instanceof String ? (String)saved : "";
        selected = catalog.restore(savedId);
        hasSelected = catalog.get(savedId) != null && !Boolean.FALSE.equals(stored.get("hasSelectedScene"));
        for (SceneCatalog.Item item : catalog.all()) {
            float[] values = item.defaults.clone();
            for (int i = 0; i < 3; i++) {
                Object value = stored.get(volumeKey(item.id, i));
                // The old single-space build stored only rain volumes globally.
                if (value == null && item.id.equals("rain")) value = stored.get("volume" + i);
                if (value instanceof Number) values[i] = SafetyPolicy.volume(((Number)value).doubleValue());
            }
            mixes.put(item.id, values);
        }
    }
    String selected() { return selected; }
    boolean hasSelected() { return hasSelected; }
    boolean select(String id) {
        if (catalog.get(id) == null) return false;
        selected = id; hasSelected = true; return true;
    }
    float[] volumes() {
        float[] values = mixes.get(selected);
        return values == null ? new float[3] : values.clone();
    }
    void remember(float[] values) {
        if (!mixes.containsKey(selected) || values == null || values.length != 3) return;
        float[] safe = new float[3];
        for (int i = 0; i < 3; i++) safe[i] = SafetyPolicy.volume(values[i]);
        mixes.put(selected, safe);
    }
    Map<String, Object> values() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasSelectedScene", hasSelected);
        if (hasSelected) result.put("sceneId", selected);
        for (Map.Entry<String, float[]> item : mixes.entrySet()) {
            for (int i = 0; i < 3; i++) result.put(volumeKey(item.getKey(), i), item.getValue()[i]);
        }
        return result;
    }
    private static String volumeKey(String id, int index) { return "scene." + id + ".volume" + index; }
}
