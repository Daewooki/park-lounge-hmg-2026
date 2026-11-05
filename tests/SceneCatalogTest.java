package ai.nc.varcospaces;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Real native selection/search/mix policies without Android service construction. */
public final class SceneCatalogTest {
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++; if (!condition) throw new AssertionError(label);
    }
    private static void near(float actual, float expected, String label) { check(Math.abs(actual - expected) < .0001f, label); }
    private static SceneCatalog.Item item(String id, String title, String titleEn, String label, String labelEn, float gain) {
        return new SceneCatalog.Item(id, title, titleEn, "", "", true,
            new String[]{"content/" + id + "/1.wav", "content/" + id + "/2.wav", "content/" + id + "/3.wav"},
            new String[]{label, "배경", "잔향"}, new String[]{labelEn, "Background", "Echo"}, new float[]{gain, .3f, .1f});
    }
    public static void main(String[] args) {
        SceneCatalog.Item rain = item("rain", "비 오는 숲", "Rain shelter", "빗소리", "Rainfall", .65f);
        SceneCatalog.Item shore = item("shore", "노을 해변", "Sunset Shore", "파도", "Waves", .7f);
        SceneCatalog.Item fire = item("fireside", "벽난로 라운지", "Fireside Lounge", "장작", "Firewood", .8f);
        SceneCatalog.Item moon = item("moon", "달 전망 라운지", "Moon Lounge", "선실", "Cabin", .5f);
        SceneCatalog library = new SceneCatalog(Arrays.asList(rain, shore, fire, moon, rain));
        check(library.all().size() == 4, "Ready scenes appear once in catalog order");
        check(library.all().get(1).id.equals("shore"), "Catalog order survives de-duplication");
        check(library.restore("moon").equals("moon"), "Saved non-rain selection is restored");
        check(library.restore("removed").equals("rain"), "Removed selection falls back to first ready scene");
        check(new SceneCatalog(Collections.emptyList()).restore("rain").isEmpty(), "Empty catalog invents no playable rain");
        check(library.search("파도").get(0).id.equals("shore"), "Korean layer search resolves shore");
        check(library.search("  SUNSET   SHORE ").get(0).id.equals("shore"), "English title search normalizes spaces and case");
        check(library.search("벽난로").get(0).id.equals("fireside"), "Korean partial-title search resolves fireplace");
        check(library.search("moon").get(0).id.equals("moon"), "Media ID search resolves moon");
        check(library.search("firewood").get(0).id.equals("fireside"), "English layer search resolves fireplace");
        check(library.search("not a scene").isEmpty(), "Unmatched query has no unrelated fallback");
        check(library.searchSelection(null, "moon").equals("moon"), "Empty voice request uses current scene");
        check(library.searchSelection("Park Lounge", "shore").equals("shore"), "App-name request uses current scene");
        check(library.searchSelection("파도", "moon").equals("shore"), "Specific search changes to the matching scene");
        check(library.searchSelection("unmatched", "rain") == null, "Unmatched play search never selects rain");
        check(shore.title(true).equals("Sunset Shore") && shore.title(false).equals("노을 해변"), "Media titles are per-item and localized");
        check(shore.subtitle(true).startsWith("Waves"), "Media subtitle uses this scene's layers");
        SceneCatalog.Item pending = new SceneCatalog.Item("pending", "", "", "", "", false, rain.files, rain.labels, rain.labelsEn, rain.defaults);
        SceneCatalog.Item missing = new SceneCatalog.Item("missing", "", "", "", "", true, new String[]{"content/a.wav", "", "content/c.wav"}, rain.labels, rain.labelsEn, rain.defaults);
        SceneCatalog.Item unsafe = new SceneCatalog.Item("unsafe", "", "", "", "", true, new String[]{"content/../a.wav", "content/b.wav", "content/c.wav"}, rain.labels, rain.labelsEn, rain.defaults);
        SceneCatalog.Item badId = item("../rain", "", "", "", "", .5f);
        check(new SceneCatalog(Arrays.asList(pending, missing, unsafe, badId, moon)).all().size() == 1,
            "Unavailable, incomplete, unsafe-path and unsafe-ID scenes are excluded from playback and browse");

        SceneSettings fresh = new SceneSettings(library, Collections.emptyMap());
        check(fresh.selected().equals("rain") && !fresh.hasSelected(), "Fresh install has a preview but no confirmed selection");
        check(!fresh.values().containsKey("sceneId"), "Reading first-run settings does not confirm a selection");
        check(fresh.select("rain") && fresh.hasSelected(), "Choosing default space confirms first selection");
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("sceneId", "rain"); legacy.put("volume0", .91f); legacy.put("volume1", .12f); legacy.put("volume2", .07f);
        SceneSettings memory = new SceneSettings(library, legacy);
        check(memory.hasSelected(), "An existing valid scene selection survives upgrade");
        near(memory.volumes()[0], .91f, "Old global volume migrates to rain");
        memory.select("shore"); near(memory.volumes()[0], .7f, "Rain migration does not leak into shore defaults");
        memory.remember(new float[]{.2f, .8f, .4f});
        memory.select("fireside"); near(memory.volumes()[0], .8f, "Fireplace starts with its own defaults");
        memory.remember(new float[]{.9f, 0, .05f});
        memory.select("moon"); near(memory.volumes()[0], .5f, "Moon starts with its own defaults");
        memory.remember(new float[]{.1f, .2f, .3f});
        memory.select("shore"); near(memory.volumes()[0], .2f, "Returning to shore restores its mix");
        memory.select("rain"); near(memory.volumes()[0], .91f, "Returning to rain retains migrated mix");
        memory.select("moon");
        SceneSettings restarted = new SceneSettings(library, memory.values());
        check(restarted.selected().equals("moon") && restarted.hasSelected(), "Restart restores last selection without a play action");
        near(restarted.volumes()[2], .3f, "Restart restores the selected scene's mix");
        restarted.select("fireside"); near(restarted.volumes()[0], .9f, "Restart preserves other scene mixes");
        check(!restarted.select("removed") && restarted.selected().equals("fireside"), "Unknown selection cannot replace active scene");
        float[] snapshot = restarted.volumes(); snapshot[0] = 0;
        near(restarted.volumes()[0], .9f, "Exposed volume arrays cannot mutate remembered settings");
        restarted.remember(new float[]{-1, 2, Float.NaN});
        near(restarted.volumes()[0], 0, "Invalid negative saved volume is bounded");
        near(restarted.volumes()[1], 1, "Invalid high saved volume is bounded");
        near(restarted.volumes()[2], 0, "Non-finite saved volume is rejected");
        legacy.put("scene.rain.volume0", .4f);
        near(new SceneSettings(library, legacy).volumes()[0], .4f, "New per-scene values override legacy migration");
        legacy.put("sceneId", "removed");
        SceneSettings removed = new SceneSettings(library, legacy);
        check(!removed.hasSelected() && removed.selected().equals("rain"), "Removed last selection reopens scene selection");
        System.out.println("Scene catalog and settings tests passed: " + assertions + " assertions.");
    }
}
