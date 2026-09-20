package com.limelight.binding.input.minecraft;

import org.junit.Test;
import static org.junit.Assert.*;

public class MinecraftControlMathTest {
    @Test public void deadzoneIsNeutral() {
        assertEquals(0, MinecraftControlMath.movementMask(5, -5, 100));
    }

    @Test public void diagonalsWork() {
        int m = MinecraftControlMath.movementMask(60, -70, 100);
        assertTrue((m & MinecraftControlMath.DIR_RIGHT) != 0);
        assertTrue((m & MinecraftControlMath.DIR_UP) != 0);
    }

    @Test public void oppositeDirectionsDontCoexistPerAxis() {
        int m = MinecraftControlMath.movementMask(-80, 0, 100);
        assertTrue((m & MinecraftControlMath.DIR_LEFT) != 0);
        assertFalse((m & MinecraftControlMath.DIR_RIGHT) != 0);
    }

    @Test public void mouseScalingClamps() {
        assertEquals(Short.MAX_VALUE, MinecraftControlMath.mouseDelta(100000, 2f, false));
        assertEquals(Short.MIN_VALUE, MinecraftControlMath.mouseDelta(100000, 2f, true));
    }
}
