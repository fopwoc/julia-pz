package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextureTableTest {
    @Test
    void growsDenseNamesAndFallsBackToSparseOnesWithoutLosingRecords() {
        var table = new TextureTable();
        var small = table.getOrCreate(3);
        var grown = table.getOrCreate(5000);
        var huge = table.getOrCreate(Integer.MAX_VALUE);
        assertSame(small, table.get(3));
        assertSame(grown, table.get(5000));
        assertSame(huge, table.get(Integer.MAX_VALUE));
        assertSame(small, table.getOrCreate(3));
        assertNull(table.get(4));
        assertNull(table.get(-2));
        assertThrows(IllegalArgumentException.class, () -> table.getOrCreate(-2));
    }

    @Test
    void deletedNamesComeBackAsFreshRecords() {
        var table = new TextureTable();
        var first = table.getOrCreate(7);
        first.target = 1;
        var sparse = table.getOrCreate(1 << 21);
        table.deleted(7);
        table.deleted(1 << 21);
        assertNull(table.get(7));
        assertNull(table.get(1 << 21));
        assertNotSame(first, table.getOrCreate(7));
        assertEquals(0, table.get(7).target());
        assertNotSame(sparse, table.getOrCreate(1 << 21));
        table.clear();
        assertNull(table.get(7));
        assertNull(table.get(1 << 21));
    }
}
