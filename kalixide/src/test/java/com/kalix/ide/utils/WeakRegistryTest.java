package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeakRegistryTest {

    @Test
    void visitsEveryRegisteredInstanceInOrder() {
        WeakRegistry<CharSequence> registry = new WeakRegistry<>();
        registry.add("first");
        registry.add(new StringBuilder("second"));

        List<String> seen = new ArrayList<>();
        registry.forEach(item -> seen.add(item.toString()));

        assertEquals(List.of("first", "second"), seen);
    }
}
