package com.limelight.binding.input.minecraft;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

public final class MinecraftTouchOverlay extends View {
    public interface InputSink {
        void key(int androidKeyCode, boolean down);
        void mouseMove(short dx, short dy);
        void mouseButton(int button, boolean down);
        void scroll(short amount);
    }

    public static final int MOUSE_LEFT = 1;
    public static final int MOUSE_RIGHT = 3;

    private final InputSink sink;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, String> pointerZones = new HashMap<>();

    private int joystickPointer = -1;
    private int lookPointer = -1;
    private float joyX;
    private float joyY;
    private float lookLastX;
    private float lookLastY;
    private int movementMask;
    private boolean controlsEnabled = false;
    private float sensitivity = 1.15f;

    public MinecraftTouchOverlay(Context context, InputSink sink) {
        super(context);
        this.sink = sink;
        setFocusable(false);
        setFocusableInTouchMode(false);
        fill.setColor(Color.argb(92, 20, 24, 30));
        stroke.setColor(Color.argb(170, 255, 255, 255));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        active.setColor(Color.argb(155, 255, 255, 255));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public void setControlsEnabled(boolean enabled) {
        if (controlsEnabled == enabled) return;
        releaseAllInputs();
        controlsEnabled = enabled;
        invalidate();
    }

    public void releaseAllInputs() {
        setMovementMask(0);
        for (String zone : pointerZones.values()) releaseZone(zone);
        pointerZones.clear();
        joystickPointer = -1;
        lookPointer = -1;
    }

    @Override protected void onDetachedFromWindow() {
        releaseAllInputs();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);

        RectF toggle = toggleRect(w, h, s);
        canvas.drawRoundRect(toggle, dp(14), dp(14), controlsEnabled ? active : fill);
        canvas.drawRoundRect(toggle, dp(14), dp(14), stroke);
        text.setTextSize(dp(12));
        canvas.drawText("MC",
                toggle.centerX(), toggle.centerY() - (text.ascent() + text.descent()) / 2f, text);

        if (!controlsEnabled) return;

        float joyCx = w * 0.145f;
        float joyCy = h * 0.72f;
        float joyR = s * 0.135f;
        if (joyX == 0 && joyY == 0) {
            joyX = joyCx;
            joyY = joyCy;
        }
        canvas.drawCircle(joyCx, joyCy, joyR, fill);
        canvas.drawCircle(joyCx, joyCy, joyR, stroke);
        canvas.drawCircle(joyX, joyY, joyR * 0.42f, active);
        text.setTextSize(s * 0.024f);
        canvas.drawText("WASD", joyCx, joyCy + joyR + s * 0.035f, text);

        drawButton(canvas, w * 0.90f, h * 0.58f, s * 0.085f, "ATK", isHeld("attack"));
        drawButton(canvas, w * 0.76f, h * 0.70f, s * 0.075f, "USE", isHeld("use"));
        drawButton(canvas, w * 0.90f, h * 0.80f, s * 0.083f, "JUMP", isHeld("jump"));
        drawButton(canvas, w * 0.30f, h * 0.80f, s * 0.065f, "SHIFT", isHeld("sneak"));
        drawButton(canvas, w * 0.12f, h * 0.48f, s * 0.062f, "CTRL", isHeld("sprint"));
        drawButton(canvas, w * 0.68f, h * 0.10f, s * 0.050f, "F3", isHeld("f3"));
        drawButton(canvas, w * 0.77f, h * 0.10f, s * 0.050f, "Q", isHeld("q"));
        drawButton(canvas, w * 0.86f, h * 0.10f, s * 0.050f, "E", isHeld("inventory"));
        drawButton(canvas, w * 0.95f, h * 0.10f, s * 0.050f, "ESC", isHeld("esc"));
        drawButton(canvas, w * 0.68f, h * 0.54f, s * 0.048f, "UP", false);
        drawButton(canvas, w * 0.68f, h * 0.65f, s * 0.048f, "DN", false);

        float barLeft = w * 0.34f;
        float barRight = w * 0.66f;
        float barTop = h * 0.89f;
        float barBottom = h * 0.985f;
        float cell = (barRight - barLeft) / 9f;
        text.setTextSize(s * 0.027f);
        for (int i = 0; i < 9; i++) {
            RectF r = new RectF(barLeft + i * cell, barTop, barLeft + (i + 1) * cell, barBottom);
            canvas.drawRoundRect(r, dp(8), dp(8), isHeld("slot" + (i + 1)) ? active : fill);
            canvas.drawRoundRect(r, dp(8), dp(8), stroke);
            canvas.drawText(String.valueOf(i + 1), r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2f, text);
        }

        text.setTextSize(s * 0.022f);
        text.setColor(Color.argb(135, 255, 255, 255));
        canvas.drawText("LOOK / MOUSE", w * 0.62f, h * 0.43f, text);
        text.setColor(Color.WHITE);
    }

    private RectF toggleRect(float w, float h, float s) {
        float size = dp(46);
        float margin = dp(10);
        return new RectF(w - margin - size, margin, w - margin, margin + size);
    }

    private void drawButton(Canvas canvas, float x, float y, float r, String label, boolean pressed) {
        canvas.drawCircle(x, y, r, pressed ? active : fill);
        canvas.drawCircle(x, y, r, stroke);
        text.setTextSize(Math.max(dp(12), r * (label.length() > 3 ? 0.37f : 0.55f)));
        canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);
        RectF toggle = toggleRect(getWidth(), getHeight(), Math.min(getWidth(), getHeight()));

        if (!controlsEnabled) {
            if (action == MotionEvent.ACTION_DOWN && toggle.contains(x, y)) {
                pointerZones.put(id, "toggle");
                return true;
            }
            if (pointerZones.containsKey(id)) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pointerZones.remove(id);
                    setControlsEnabled(true);
                }
                return true;
            }
            return false;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            handleDown(id, x, y);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) handleMove(event.getPointerId(i), event.getX(i), event.getY(i));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            handleUp(id);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            releaseAllInputs();
        }
        invalidate();
        return true;
    }

    private void handleDown(int id, float x, float y) {
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);
        if (toggleRect(w, h, s).contains(x, y)) {
            pointerZones.put(id, "toggle");
            setControlsEnabled(false);
            return;
        }
        float joyCx = w * 0.145f;
        float joyCy = h * 0.72f;
        float joyR = s * 0.135f;
        if (joystickPointer < 0 && distance(x, y, joyCx, joyCy) <= joyR * 1.30f) {
            joystickPointer = id;
            pointerZones.put(id, "joystick");
            updateJoystick(x, y);
            return;
        }
        String zone = hitButton(x, y, w, h, s);
        if (zone != null) {
            pointerZones.put(id, zone);
            pressZone(zone);
            return;
        }
        int slot = hitHotbar(x, y, w, h);
        if (slot > 0) {
            String z = "slot" + slot;
            pointerZones.put(id, z);
            pressZone(z);
            return;
        }
        if (x > w * 0.40f && lookPointer < 0) {
            lookPointer = id;
            lookLastX = x;
            lookLastY = y;
            pointerZones.put(id, "look");
        } else {
            pointerZones.put(id, "consume");
        }
    }

    private void handleMove(int id, float x, float y) {
        String zone = pointerZones.get(id);
        if (zone == null) return;
        if ("joystick".equals(zone)) {
            updateJoystick(x, y);
        } else if ("look".equals(zone) && id == lookPointer) {
            float dx = x - lookLastX;
            float dy = y - lookLastY;
            lookLastX = x;
            lookLastY = y;
            short sx = MinecraftControlMath.mouseDelta(dx, sensitivity, false);
            short sy = MinecraftControlMath.mouseDelta(dy, sensitivity, false);
            if (sx != 0 || sy != 0) sink.mouseMove(sx, sy);
        }
    }

    private void handleUp(int id) {
        String zone = pointerZones.remove(id);
        if (zone == null) return;
        if ("joystick".equals(zone)) {
            joystickPointer = -1;
            joyX = getWidth() * 0.145f;
            joyY = getHeight() * 0.72f;
            setMovementMask(0);
        } else if ("look".equals(zone)) {
            if (lookPointer == id) lookPointer = -1;
        } else if (!"toggle".equals(zone) && !"consume".equals(zone)) {
            releaseZone(zone);
        }
    }

    private void updateJoystick(float x, float y) {
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);
        float cx = w * 0.145f;
        float cy = h * 0.72f;
        float r = s * 0.135f;
        float dx = x - cx;
        float dy = y - cy;
        float d = (float)Math.sqrt(dx * dx + dy * dy);
        if (d > r && d > 0f) {
            dx = dx / d * r;
            dy = dy / d * r;
        }
        joyX = cx + dx;
        joyY = cy + dy;
        setMovementMask(MinecraftControlMath.movementMask(dx, dy, r));
    }

    private void setMovementMask(int newMask) {
        if (movementMask == newMask) return;
        updateDirection(MinecraftControlMath.DIR_UP, KeyEvent.KEYCODE_W, newMask);
        updateDirection(MinecraftControlMath.DIR_LEFT, KeyEvent.KEYCODE_A, newMask);
        updateDirection(MinecraftControlMath.DIR_DOWN, KeyEvent.KEYCODE_S, newMask);
        updateDirection(MinecraftControlMath.DIR_RIGHT, KeyEvent.KEYCODE_D, newMask);
        movementMask = newMask;
    }

    private void updateDirection(int bit, int key, int newMask) {
        boolean oldDown = (movementMask & bit) != 0;
        boolean newDown = (newMask & bit) != 0;
        if (oldDown != newDown) sink.key(key, newDown);
    }

    private String hitButton(float x, float y, float w, float h, float s) {
        if (inside(x, y, w * 0.12f, h * 0.48f, s * 0.062f)) return "sprint";
        if (inside(x, y, w * 0.30f, h * 0.80f, s * 0.065f)) return "sneak";
        if (inside(x, y, w * 0.90f, h * 0.58f, s * 0.085f)) return "attack";
        if (inside(x, y, w * 0.76f, h * 0.70f, s * 0.075f)) return "use";
        if (inside(x, y, w * 0.90f, h * 0.80f, s * 0.083f)) return "jump";
        if (inside(x, y, w * 0.68f, h * 0.54f, s * 0.048f)) return "wheel_up";
        if (inside(x, y, w * 0.68f, h * 0.65f, s * 0.048f)) return "wheel_down";
        if (inside(x, y, w * 0.68f, h * 0.10f, s * 0.050f)) return "f3";
        if (inside(x, y, w * 0.77f, h * 0.10f, s * 0.050f)) return "q";
        if (inside(x, y, w * 0.86f, h * 0.10f, s * 0.050f)) return "inventory";
        if (inside(x, y, w * 0.95f, h * 0.10f, s * 0.050f)) return "esc";
        return null;
    }

    private int hitHotbar(float x, float y, float w, float h) {
        float left = w * 0.34f;
        float right = w * 0.66f;
        float top = h * 0.89f;
        float bottom = h * 0.985f;
        if (x < left || x > right || y < top || y > bottom) return 0;
        int slot = (int)((x - left) / ((right - left) / 9f)) + 1;
        return Math.max(1, Math.min(9, slot));
    }

    private void pressZone(String zone) {
        switch (zone) {
            case "sprint": sink.key(KeyEvent.KEYCODE_CTRL_LEFT, true); break;
            case "sneak": sink.key(KeyEvent.KEYCODE_SHIFT_LEFT, true); break;
            case "attack": sink.mouseButton(MOUSE_LEFT, true); break;
            case "use": sink.mouseButton(MOUSE_RIGHT, true); break;
            case "jump": sink.key(KeyEvent.KEYCODE_SPACE, true); break;
            case "inventory": sink.key(KeyEvent.KEYCODE_E, true); break;
            case "q": sink.key(KeyEvent.KEYCODE_Q, true); break;
            case "esc": sink.key(KeyEvent.KEYCODE_ESCAPE, true); break;
            case "f3": sink.key(KeyEvent.KEYCODE_F3, true); break;
            case "wheel_up": sink.scroll((short)120); break;
            case "wheel_down": sink.scroll((short)-120); break;
            default:
                if (zone.startsWith("slot")) sink.key(KeyEvent.KEYCODE_1 + Integer.parseInt(zone.substring(4)) - 1, true);
        }
    }

    private void releaseZone(String zone) {
        switch (zone) {
            case "sprint": sink.key(KeyEvent.KEYCODE_CTRL_LEFT, false); break;
            case "sneak": sink.key(KeyEvent.KEYCODE_SHIFT_LEFT, false); break;
            case "attack": sink.mouseButton(MOUSE_LEFT, false); break;
            case "use": sink.mouseButton(MOUSE_RIGHT, false); break;
            case "jump": sink.key(KeyEvent.KEYCODE_SPACE, false); break;
            case "inventory": sink.key(KeyEvent.KEYCODE_E, false); break;
            case "q": sink.key(KeyEvent.KEYCODE_Q, false); break;
            case "esc": sink.key(KeyEvent.KEYCODE_ESCAPE, false); break;
            case "f3": sink.key(KeyEvent.KEYCODE_F3, false); break;
            case "wheel_up":
            case "wheel_down": break;
            default:
                if (zone.startsWith("slot")) sink.key(KeyEvent.KEYCODE_1 + Integer.parseInt(zone.substring(4)) - 1, false);
        }
    }

    private boolean isHeld(String zone) { return pointerZones.containsValue(zone); }
    private boolean inside(float x, float y, float cx, float cy, float r) { return distance(x, y, cx, cy) <= r; }
    private float distance(float x, float y, float cx, float cy) {
        float dx = x - cx;
        float dy = y - cy;
        return (float)Math.sqrt(dx * dx + dy * dy);
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
