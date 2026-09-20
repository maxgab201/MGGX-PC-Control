package com.limelight.binding.input.minecraft;

public final class MinecraftControlMath {
    private MinecraftControlMath() {}

    public static final int DIR_LEFT = 1;
    public static final int DIR_RIGHT = 1 << 1;
    public static final int DIR_UP = 1 << 2;
    public static final int DIR_DOWN = 1 << 3;

    public static int movementMask(float dx, float dy, float radius) {
        float threshold = radius * 0.28f;
        int mask = 0;
        if (dx < -threshold) mask |= DIR_LEFT;
        if (dx > threshold) mask |= DIR_RIGHT;
        if (dy < -threshold) mask |= DIR_UP;
        if (dy > threshold) mask |= DIR_DOWN;
        return mask;
    }

    public static short mouseDelta(float raw, float sensitivity, boolean invert) {
        int v = Math.round(raw * sensitivity * (invert ? -1f : 1f));
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        return (short)v;
    }
}
