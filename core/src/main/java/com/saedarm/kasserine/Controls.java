package com.saedarm.kasserine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerMapping;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;

/**
 * Every way to play, merged into one set of values: keyboard, mouse and game
 * controller. Whichever you used last decides what the HUD prompts say.
 *
 * Keyboard:   WASD or arrow keys move.
 *             Mouse aims, left click fires, right click rolls.
 *             No mouse? Space, Z or J fires at the nearest enemy; Shift, X or K rolls.
 * Controller: left stick moves, right stick aims and fires (or hold R1/R2),
 *             A or L1 rolls, Start pauses.
 *
 * All directions come out in world space (x across the canyon, z up it).
 * Screen right is world -x with this camera, hence the minus signs.
 */
public class Controls extends InputAdapter {

    public enum Device { KEYBOARD, MOUSE, CONTROLLER }

    private static final float DEADZONE = 0.25f;
    private static final float MOUSE_IDLE = 2.5f; // seconds without mouse use before keyboard aim takes over

    // ---- results of poll() -----------------------------------------------------

    public float moveX, moveZ, aimX, aimZ;
    public boolean fire, dodge;
    /** One-shot menu presses, true only for the frame they happened. */
    public boolean pause, confirm, left, right;
    /** 1, 2 or 3 pressed this frame on the upgrade screen, else 0. */
    public int pick;
    /** What you used last. */
    public Device device = Device.MOUSE;

    // ---- internals ------------------------------------------------------------

    private float mouseIdle = 99f;
    private boolean dodgeKey, pauseKey, confirmKey, leftKey, rightKey;
    private int pickKey;
    // Controller buttons last frame, so we only react to the press, not the hold.
    private boolean padRoll, padStart, padConfirm, padLeft, padRight;

    private final Plane ground = new Plane(Vector3.Y, 0);
    private final Vector3 hit = new Vector3();

    // ---- keyboard and mouse events --------------------------------------------

    @Override
    public boolean keyDown(int key) {
        switch (key) {
            case Input.Keys.ESCAPE: case Input.Keys.P:
                pauseKey = true; return true;
            case Input.Keys.SHIFT_LEFT: case Input.Keys.SHIFT_RIGHT: case Input.Keys.X: case Input.Keys.K:
                dodgeKey = true; markKeyboard(); return true;
            case Input.Keys.ENTER: case Input.Keys.NUMPAD_ENTER: case Input.Keys.SPACE: case Input.Keys.Z: case Input.Keys.J:
                confirmKey = true; return false; // also used as fire, which is polled
            case Input.Keys.LEFT: case Input.Keys.A:
                leftKey = true; return false;
            case Input.Keys.RIGHT: case Input.Keys.D:
                rightKey = true; return false;
            case Input.Keys.NUM_1: case Input.Keys.NUMPAD_1: pickKey = 1; return true;
            case Input.Keys.NUM_2: case Input.Keys.NUMPAD_2: pickKey = 2; return true;
            case Input.Keys.NUM_3: case Input.Keys.NUMPAD_3: pickKey = 3; return true;
        }
        return false;
    }

    @Override
    public boolean mouseMoved(int x, int y) {
        mouseIdle = 0;
        device = Device.MOUSE;
        return false;
    }

    @Override
    public boolean touchDown(int x, int y, int pointer, int button) {
        mouseIdle = 0;
        device = Device.MOUSE;
        if (button == Input.Buttons.RIGHT) { dodgeKey = true; return true; }
        return false;
    }

    @Override
    public boolean touchDragged(int x, int y, int pointer) {
        mouseIdle = 0;
        return false;
    }

    private void markKeyboard() {
        if (device != Device.MOUSE || mouseIdle > MOUSE_IDLE) device = Device.KEYBOARD;
    }

    // ---- once per frame ---------------------------------------------------------

    public void poll(float dt, Battle battle, Camera cam) {
        mouseIdle += dt;
        Battle.Soldier p = battle.player;
        Input in = Gdx.input;

        // Keyboard movement, in screen terms first.
        float kx = 0, ky = 0;
        if (in.isKeyPressed(Input.Keys.D) || in.isKeyPressed(Input.Keys.RIGHT)) kx += 1;
        if (in.isKeyPressed(Input.Keys.A) || in.isKeyPressed(Input.Keys.LEFT)) kx -= 1;
        if (in.isKeyPressed(Input.Keys.W) || in.isKeyPressed(Input.Keys.UP)) ky += 1;
        if (in.isKeyPressed(Input.Keys.S) || in.isKeyPressed(Input.Keys.DOWN)) ky -= 1;
        boolean keyFire = in.isKeyPressed(Input.Keys.SPACE) || in.isKeyPressed(Input.Keys.Z) || in.isKeyPressed(Input.Keys.J);
        if (kx != 0 || ky != 0 || keyFire) markKeyboard();

        // Controller.
        float lx = 0, ly = 0, rx = 0, ry = 0;
        boolean padFire = false, roll = false, start = false, ok = false, pl = false, pr = false;
        Controller c = controller();
        if (c != null) {
            ControllerMapping m = c.getMapping();
            lx = axis(c, m.axisLeftX); ly = -axis(c, m.axisLeftY);   // controllers report up as negative
            rx = axis(c, m.axisRightX); ry = -axis(c, m.axisRightY);
            if (button(c, m.buttonDpadLeft)) lx = -1;
            if (button(c, m.buttonDpadRight)) lx = 1;
            if (button(c, m.buttonDpadUp)) ly = 1;
            if (button(c, m.buttonDpadDown)) ly = -1;
            padFire = button(c, m.buttonR1) || button(c, m.buttonR2);
            roll = button(c, m.buttonA) || button(c, m.buttonL1);
            start = button(c, m.buttonStart) || button(c, m.buttonBack);
            ok = button(c, m.buttonA);
            pl = lx < -0.6f;
            pr = lx > 0.6f;
            if (len(lx, ly) > 0 || len(rx, ry) > 0 || padFire || roll || start) device = Device.CONTROLLER;
        }

        // ---- movement: everything adds up, capped at full speed ----
        float sx = kx + lx;
        float sy = ky + ly;
        float l = len(sx, sy);
        if (l > 1) { sx /= l; sy /= l; }
        moveX = -sx;
        moveZ = sy;

        // ---- aim and fire: the most direct source wins ----
        fire = false;
        if (len(rx, ry) > 0) {
            aimX = -rx; aimZ = ry;
            fire = len(rx, ry) > 0.5f || padFire;
        } else if (mouseIdle < MOUSE_IDLE && device == Device.MOUSE) {
            Ray ray = cam.getPickRay(in.getX(), in.getY());
            if (Intersector.intersectRayPlane(ray, ground, hit)) { aimX = hit.x - p.x; aimZ = hit.z - p.z; }
            fire = in.isButtonPressed(Input.Buttons.LEFT) || keyFire;
        } else {
            // Keyboard only, or a controller without the right stick touched:
            // aim at the nearest German, or the way you're moving if there's nobody close.
            Battle.Enemy t = nearest(battle, 18f);
            if (t != null) { aimX = t.x - p.x; aimZ = t.z - p.z; }
            else if (l > 0.2f) { aimX = moveX; aimZ = moveZ; }
            else { aimX = p.aimX; aimZ = p.aimZ; }
            fire = keyFire || padFire;
        }

        // ---- one-shot presses ----
        dodge = dodgeKey || (roll && !padRoll);
        pause = pauseKey || (start && !padStart);
        confirm = confirmKey || (ok && !padConfirm);
        left = leftKey || (pl && !padLeft);
        right = rightKey || (pr && !padRight);
        pick = pickKey;

        dodgeKey = pauseKey = confirmKey = leftKey = rightKey = false;
        pickKey = 0;
        padRoll = roll; padStart = start; padConfirm = ok; padLeft = pl; padRight = pr;
    }

    public void clear() {
        dodgeKey = pauseKey = confirmKey = leftKey = rightKey = false;
        pickKey = 0;
    }

    // ---- helpers ------------------------------------------------------------

    private static Controller controller() {
        try {
            Controller c = Controllers.getCurrent();
            if (c == null && Controllers.getControllers().size > 0) c = Controllers.getControllers().first();
            return c;
        } catch (Throwable t) {
            return null; // no controller backend on this platform
        }
    }

    private static float axis(Controller c, int code) {
        if (code == ControllerMapping.UNDEFINED) return 0;
        float v = c.getAxis(code);
        return Math.abs(v) < DEADZONE ? 0 : v;
    }

    private static boolean button(Controller c, int code) {
        return code != ControllerMapping.UNDEFINED && c.getButton(code);
    }

    private static float len(float x, float y) { return (float) Math.sqrt(x * x + y * y); }

    private static Battle.Enemy nearest(Battle b, float range) {
        Battle.Enemy best = null;
        float bd = range * range;
        for (Battle.Enemy e : b.enemies) {
            float dx = e.x - b.player.x, dz = e.z - b.player.z;
            float d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }
}
