import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;
enum Light { RED, GREEN }
enum Door { OPEN, SHUT }

public class Design extends Module {
    public Design() {
        HwEnum<Light> a = input("a", HwEnum.of(Light.class));
        HwEnum<Door> b = input("b", HwEnum.of(Door.class));
        output("y", Bool.TYPE).set(a.eq(b)); // MISTAKE
    }
}
