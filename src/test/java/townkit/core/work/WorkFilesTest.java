package townkit.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WorkFilesTest {
    private final List<String> lines = new ArrayList<>();
    private final WorkLog log = new WorkLog(this.lines::add);

    /** Reads {"action", "id", "n"} into an add / change / remove of a (String, Integer) table. */
    private static final WorkFiles.WorkCodec<String, Integer> CODEC = (file, json) -> {
        String id = json.has("id") ? json.get("id").getAsString() : null;
        if (id == null) throw new JsonParseException("missing \"id\"");
        return switch (WorkFiles.action(json)) {
            case ADD -> WorkChange.add(file.toString(), id, json.get("n").getAsInt());
            case CHANGE -> WorkChange.change(file.toString(), id, old -> json.get("n").getAsInt());
            case REMOVE -> WorkChange.remove(file.toString(), id);
        };
    };

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("pack", path);
    }

    @Test
    void badActionSkipsFile() {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("bad"), json("{\"action\":\"explode\",\"id\":\"x\",\"n\":1}"));
        files.put(id("none"), json("{\"id\":\"x\",\"n\":1}"));
        files.put(id("good"), json("{\"action\":\"add\",\"id\":\"x\",\"n\":1}"));
        List<WorkChange<String, Integer>> out = WorkFiles.parse("test", files, CODEC, this.log);
        assertEquals(1, out.size());
        assertEquals("x", out.get(0).key());
        assertEquals(2, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("pack:bad"), this.lines.get(0));
        assertTrue(this.lines.get(1).contains("pack:none"), this.lines.get(1));
    }

    @Test
    void missingFieldSkipsFile() {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("noid"), json("{\"action\":\"add\",\"n\":1}"));
        files.put(id("notobject"), json("[1,2]"));
        files.put(id("nonumber"), json("{\"action\":\"add\",\"id\":\"y\"}")); // codec throws NullPointerException
        List<WorkChange<String, Integer>> out = WorkFiles.parse("test", files, CODEC, this.log);
        assertTrue(out.isEmpty());
        assertEquals(3, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("pack:noid"));
    }

    @Test
    void orderFollowsMapOrder() {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("one"), json("{\"action\":\"add\",\"id\":\"a\",\"n\":1}"));
        files.put(id("two"), json("{\"action\":\"change\",\"id\":\"a\",\"n\":2}"));
        files.put(id("three"), json("{\"action\":\"remove\",\"id\":\"b\"}"));
        List<WorkChange<String, Integer>> out = WorkFiles.parse("test", files, CODEC, this.log);
        assertEquals(List.of(WorkChange.Kind.ADD, WorkChange.Kind.CHANGE, WorkChange.Kind.REMOVE),
            out.stream().map(WorkChange::kind).toList());
        assertTrue(this.lines.isEmpty());
    }
}
