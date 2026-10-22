package com.saedarm.kasserine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;

/**
 * Twin virtual joysticks plus a dodge button. Touch anywhere on the left half
 * to move, anywhere on the right half to aim and fire. Each stick appears where
 * your thumb lands. The dodge button sits at a fixed spot on the right side.
 * Values are screen-space: x right, y up, -1..1.
 */
public class Sticks extends InputAdapter {

    public static class Stick {
        public int pointer = -1;
        public float originX, originY, knobX, knobY;  // screen pixels, y up
        public float x, y;                            // -1..1
        public boolean active() { return pointer >= 0; }
    }

    public final Stick move = new Stick();
    public final Stick aim = new Stick();
    public float radius;  // pixels for a full push

    /** Dodge button, screen pixels with y up. */
    public float dodgeX, dodgeY, dodgeR;
    /** Set when the button is pressed. The game reads it and clears it. */
    public boolean dodgePressed;

    public void resize(int width, int height) {
        radius = height * 0.12f;
        dodgeR = height * 0.085f;
        dodgeX = width - height * 0.14f;
        dodgeY = height * 0.40f;
    }

    @Override
    public boolean touchDown(int sx, int sy, int pointer, int button) {
        float y = Gdx.graphics.getHeight() - sy;
        // A slightly bigger hit area than the drawn circle, so it's easy to hit with a thumb.
        float dx = sx - dodgeX, dy = y - dodgeY, r = dodgeR * 1.3f;
        if (dx * dx + dy * dy < r * r) { dodgePressed = true; return true; }

        Stick s = sx < Gdx.graphics.getWidth() / 2 ? move : aim;
        if (s.active()) return false;
        s.pointer = pointer;
        s.originX = s.knobX = sx;
        s.originY = s.knobY = y;
        s.x = s.y = 0;
        return true;
    }

    @Override
    public boolean touchDragged(int sx, int sy, int pointer) {
        Stick s = pointer == move.pointer ? move : pointer == aim.pointer ? aim : null;
        if (s == null) return false;
        float dx = sx - s.originX;
        float dy = (Gdx.graphics.getHeight() - sy) - s.originY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > radius) { dx = dx / len * radius; dy = dy / len * radius; }
        s.knobX = s.originX + dx;
        s.knobY = s.originY + dy;
        s.x = dx / radius;
        s.y = dy / radius;
        return true;
    }

    @Override
    public boolean touchUp(int sx, int sy, int pointer, int button) {
        Stick s = pointer == move.pointer ? move : pointer == aim.pointer ? aim : null;
        if (s == null) return false;
        s.pointer = -1;
        s.x = s.y = 0;
        return true;
    }

    public void clear() {
        move.pointer = aim.pointer = -1;
        move.x = move.y = aim.x = aim.y = 0;
        dodgePressed = false;
    }
}
