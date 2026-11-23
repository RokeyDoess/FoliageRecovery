package dev.foliagerecovery;

import org.bukkit.block.data.type.Leaves;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LeafStatesTest {
    private Leaves state(Map<String,Object> properties) {
        return (Leaves) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Leaves.class}, (p,m,a) -> {
            return switch (m.getName()) {
                case "getDistance" -> properties.get("distance");
                case "isPersistent" -> properties.get("persistent");
                case "isWaterlogged" -> properties.get("waterlogged");
                case "setDistance" -> { properties.put("distance", a[0]); yield null; }
                case "setPersistent" -> { properties.put("persistent", a[0]); yield null; }
                case "setWaterlogged" -> { properties.put("waterlogged", a[0]); yield null; }
                default -> throw new AssertionError("Unexpected leaf property access: " + m.getName());
            };
        });
    }
    @Test void restorationPreservesWaterloggingDistanceAndPersistence() {
        Map<String,Object> original = new HashMap<>(Map.of("distance", 3, "persistent", false, "waterlogged", true));
        Map<String,Object> replacement = new HashMap<>(Map.of("distance", 7, "persistent", true, "waterlogged", false));
        LeafStates.copyProperties(state(original), state(replacement));
        assertEquals(original, replacement);
    }
}
