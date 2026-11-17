package townkit.core.anim;

import com.alex.test.entity.NpcEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * The clip Towntopia plays for one of its animation states ({@code VillagerAnimationState}), read fail-soft from
 * Towntopia's own clip constants: {@code NpcEntity.ANIM_<STATE>}, or an alias ({@link #ALIASES}). The constant is a
 * {@link RawAnimation} or an array of variants (the first is used); the clip is its first stage's animation name.
 * A state without such a constant (Towntopia's cook moves) uses the state name in lower case, Towntopia's own naming
 * for those. Server and client; kept once read.
 */
public final class TowntopiaMoves {
    private static final Map<String, String> CLIPS = new ConcurrentHashMap<>();
    /**
     * States whose clip constant has another name, as Towntopia picks them ({@code NpcEntity.gestureClip}): WAVE plays
     * its cheer clip. Towntopia's seated variants (THINK, SHRUG, ARM_ON_HIP and WAVE on a chair) aren't followed: a
     * TownKit play uses the standing clip.
     */
    static final Map<String, String> ALIASES = Map.of("WAVE", "CHEER");

    private TowntopiaMoves() {
    }

    /** The clip name for {@code state} ({@code "SALUTE"} -> {@code "salute"}). */
    public static String clip(String state) {
        return CLIPS.computeIfAbsent(state, TowntopiaMoves::find);
    }

    private static String find(String state) {
        String fromField = fromField(state);
        return fromField != null ? fromField : state.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String fromField(String state) {
        try {
            Field f = NpcEntity.class.getDeclaredField(constant(state));
            if (!Modifier.isStatic(f.getModifiers())) return null;
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof RawAnimation[] variants && variants.length > 0) v = variants[0];
            if (v instanceof RawAnimation raw && !raw.getAnimationStages().isEmpty()) return raw.getAnimationStages().get(0).animationName();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // no such constant (or it can't be read): the lower-case name
        }
        return null;
    }
    /** The name of Towntopia's clip constant for {@code state} ({@link #ALIASES} first). */
    static String constant(String state) {
        return "ANIM_" + ALIASES.getOrDefault(state, state);
    }
}
