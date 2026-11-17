package townkit.core.hook;

import javax.annotation.Nullable;
import org.objectweb.asm.Type;

/**
 * One generated hook point: a Towntopia method with a "before" and an "after" side (constructors: after only).
 *
 * @param id      dense index used by the generated code
 * @param key     stable lookup key: {@code <class relative to com.alex.test>#<method>(<simple param types>)}
 * @param owner   internal class name of the Towntopia class
 * @param name    method name ({@code <init>} for constructors)
 * @param desc    JVM method descriptor
 * @param isStatic whether the method is static (then {@code self} is null)
 * @param access  public / protected / package / private
 * @param thread  null for the server thread; otherwise e.g. "worker" (listeners must be thread-safe)
 * @param ctor    a constructor: only has an after side (fires once the object is built)
 * @param client  client-only code (rendering, screens): never loaded on a dedicated server
 */
public record HookInfo(int id, String key, String owner, String name, String desc, boolean isStatic, String access,
                       @Nullable String thread, boolean ctor, boolean client) {
    /** Return type name: "void", "int", "com.alex.test....", "java.lang.String[]". */
    public String returns() {
        return Type.getReturnType(this.desc).getClassName();
    }

    public boolean returnsVoid() {
        return this.desc.endsWith(")V");
    }

    public String ownerClassName() {
        return this.owner.replace('/', '.');
    }
}
