package com.saedarm.kasserine;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;

/**
 * Draws the battle and handles input. Everything is greybox: boxes, cylinders
 * and spheres from ModelBuilder. Swap in glTF models later without touching Battle.
 *
 * Each frame: read input, step the Battle, then turn its events into effects
 * (Fx), sounds (Sfx), screen shake and short freeze frames.
 */
public class Kasserine extends ApplicationAdapter {

    private static final long ATTR = Usage.Position | Usage.Normal;

    private Battle battle;
    private Sticks sticks;
    private Controls controls;
    private Fx fx;
    private Sfx sfx;
    private boolean paused;
    private int seenDay = -1;

    // Menus
    private int selected = 1;        // highlighted upgrade card for keyboard/controller
    private float menuDelay;         // ignore menu presses briefly, so mashing fire doesn't skip a screen

    // Game feel
    private float freeze;         // seconds left of a freeze frame (hit-stop)
    private float shake;          // 0..1 screen shake strength
    private float kickX, kickZ;   // camera recoil from your own shots
    private float lineFlash;      // HUD flash when someone gets past you

    private PerspectiveCamera cam;
    private final Vector3 camTarget = new Vector3(0, 0, 10), camGoal = new Vector3();
    private ModelBatch modelBatch;
    private Environment env;
    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private BitmapFont font;
    private final GlyphLayout layout = new GlyphLayout();
    private final Matrix4 hudMatrix = new Matrix4();
    private float u; // HUD unit, scales with screen height

    private final Array<Model> models = new Array<>();
    private Model box, sand, rockModel, gi, giHurt, kraut, krautHit, tankHull, tankTurret, tankHit,
            tracer, enemyTracer, shell, warn, warnDisc;
    private Model[] wallModels;
    private final Array<ModelInstance> scenery = new Array<>();

    // Reused instances, one pool per look. Index i is the i-th thing drawn this frame.
    private final Array<ModelInstance> pInfantry = new Array<>(), pInfantryHit = new Array<>(),
            pHull = new Array<>(), pTurret = new Array<>(), pTankHit = new Array<>(),
            pTracer = new Array<>(), pEnemyTracer = new Array<>(), pShell = new Array<>(), pGun = new Array<>(),
            pWarn = new Array<>(), pWarnDisc = new Array<>();
    private ModelInstance playerInst, playerHurtInst;

    @Override
    public void create() {
        Application.ApplicationType type = Gdx.app.getType();
        boolean touchScreen = type == Application.ApplicationType.Android || type == Application.ApplicationType.iOS;
        battle = new Battle();
        sticks = new Sticks();
        controls = new Controls(sticks, touchScreen);
        fx = new Fx();
        sfx = new Sfx();

        cam = new PerspectiveCamera(40, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.near = 1f;
        cam.far = 200f;

        modelBatch = new ModelBatch();
        env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.32f, 0.28f, 0.27f, 1f));
        env.add(new DirectionalLight().set(0.85f, 0.68f, 0.5f, -0.6f, -1f, 0.25f));

        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = new BitmapFont();
        font.getRegion().getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        buildModels();
        buildScenery();

        // Order matters: Controls sees everything first (keys, right-click roll),
        // then taps on menus, then the touch sticks get whatever is left.
        InputMultiplexer input = new InputMultiplexer(controls, new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int pointer, int button) { return tap(x, y); }
        });
        if (touchScreen) input.addProcessor(sticks);
        Gdx.input.setInputProcessor(input);
        Gdx.input.setCatchKey(com.badlogic.gdx.Input.Keys.BACK, true);

        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    // ---- models -------------------------------------------------------------

    private Model make(Model m) { models.add(m); return m; }

    private Material mat(String hex) { return new Material(ColorAttribute.createDiffuse(Color.valueOf(hex))); }

    private Material glow(String hex) {
        Color c = Color.valueOf(hex);
        return new Material(ColorAttribute.createDiffuse(c), ColorAttribute.createEmissive(c));
    }

    private void buildModels() {
        ModelBuilder mb = new ModelBuilder();
        box = make(mb.createBox(1, 1, 1, mat("#5a4632"), ATTR));
        sand = make(mb.createBox(1, 1, 1, mat("#a88250"), ATTR));
        rockModel = make(mb.createBox(1, 1, 1, mat("#8a6a48"), ATTR));
        wallModels = new Model[] {
            make(mb.createBox(1, 1, 1, mat("#9c7650"), ATTR)),
            make(mb.createBox(1, 1, 1, mat("#87653f"), ATTR)),
            make(mb.createBox(1, 1, 1, mat("#a8845a"), ATTR)),
        };
        gi = make(mb.createCylinder(0.7f, 1.6f, 0.7f, 12, mat("#4b5320"), ATTR));        // olive drab
        giHurt = make(mb.createCylinder(0.7f, 1.6f, 0.7f, 12, mat("#d04030"), ATTR));
        kraut = make(mb.createCylinder(0.7f, 1.6f, 0.7f, 12, mat("#4e4632"), ATTR));     // Afrika Korps khaki
        krautHit = make(mb.createCylinder(0.7f, 1.6f, 0.7f, 12, mat("#ffffff"), ATTR));
        tankHull = make(mb.createBox(1, 1, 1, mat("#7d7356"), ATTR));
        tankTurret = make(mb.createBox(1, 1, 1, mat("#6a6248"), ATTR));
        tankHit = make(mb.createBox(1, 1, 1, mat("#ffffff"), ATTR));
        tracer = make(mb.createSphere(0.22f, 0.22f, 0.22f, 6, 6, glow("#ffe27a"), ATTR));
        enemyTracer = make(mb.createSphere(0.22f, 0.22f, 0.22f, 6, 6, glow("#ff5a3a"), ATTR));
        shell = make(mb.createSphere(0.45f, 0.45f, 0.45f, 8, 8, glow("#ff8a2a"), ATTR));
        warn = make(mb.createBox(1, 1, 1, glow("#c81e14"), ATTR));
        warnDisc = make(mb.createCylinder(1, 1, 1, 24, glow("#c81e14"), ATTR));

        playerInst = new ModelInstance(gi);
        playerHurtInst = new ModelInstance(giHurt);
    }

    private void addBox(Model m, float x, float y, float z, float w, float h, float d) {
        ModelInstance i = new ModelInstance(m);
        i.transform.setToTranslation(x, y, z).scale(w, h, d);
        scenery.add(i);
    }

    private void buildScenery() {
        float len = Battle.LENGTH;
        addBox(sand, 0, -0.5f, len / 2, 80, 1, len + 40);

        // Canyon walls: blocks stepping along each side, following halfWidth().
        java.util.Random r = new java.util.Random(42);
        for (float z = -10; z < len + 16; z += 2f) {
            float hw = Battle.halfWidth(z);
            for (int side = -1; side <= 1; side += 2) {
                float h = 2f + r.nextFloat() * 3f;
                float w = 3f + r.nextFloat() * 2f;
                Model m = wallModels[r.nextInt(wallModels.length)];
                addBox(m, side * (hw + w / 2f), h / 2f, z, w, h, 2.3f);
                // A second, taller ridge behind for depth.
                float h2 = 4f + r.nextFloat() * 4f;
                addBox(m, side * (hw + w + 2f), h2 / 2f, z, 4f, h2, 2.3f);
            }
        }
        for (Battle.Rock rock : battle.rocks) {
            float h = 0.8f + rock.r * 0.8f;
            ModelInstance i = new ModelInstance(rockModel);
            i.transform.setToTranslation(rock.x, h / 2f, rock.z).rotate(Vector3.Y, rock.x * 37f).scale(rock.r * 1.6f, h, rock.r * 1.6f);
            scenery.add(i);
        }
        // Sandbags at the line you're holding.
        for (float x = -7; x <= 7; x += 1.5f) addBox(box, x, 0.3f, 0.2f, 1.3f, 0.6f, 0.6f);
    }

    private ModelInstance pooled(Array<ModelInstance> pool, Model m, int i) {
        while (pool.size <= i) pool.add(new ModelInstance(m));
        return pool.get(i);
    }

    /** Degrees to rotate around Y so local +z points along (fx, fz). */
    private static float yaw(float fx, float fz) { return MathUtils.atan2(fx, fz) * MathUtils.radiansToDegrees; }

    // ---- frame --------------------------------------------------------------

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 1 / 30f);
        if (battle.dayCounter != seenDay) { seenDay = battle.dayCounter; fx.clear(); }

        // A freeze frame stops the whole world for a few hundredths of a second.
        // It's the single cheapest way to make a hit feel heavy.
        menuDelay = Math.max(0, menuDelay - dt);
        if (freeze > 0) freeze -= dt;
        else {
            controls.poll(dt, battle, cam);
            menus();
            if (!paused) {
                step(dt);
                fx.update(dt);
            }
        }
        shake = Math.max(0, shake - dt * 2.5f);
        kickX *= Math.max(0, 1 - dt * 18f);
        kickZ *= Math.max(0, 1 - dt * 18f);
        lineFlash = Math.max(0, lineFlash - dt);

        Battle.Soldier p = battle.player;
        camTarget.lerp(camGoal.set(p.x * 0.6f, 0, p.z + 5f), Math.min(1, dt * 5f));
        float sh = shake * shake * 0.7f;
        cam.position.set(camTarget.x + kickX + MathUtils.random(-sh, sh), 19f + MathUtils.random(-sh, sh) * 0.5f,
                camTarget.z - 12f + kickZ + MathUtils.random(-sh, sh));
        cam.up.set(Vector3.Y);
        cam.lookAt(camTarget.x, 0, camTarget.z);
        cam.update();

        Gdx.gl.glClearColor(0.83f, 0.68f, 0.48f, 1f); // dusty sky
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        modelBatch.begin(cam);
        modelBatch.render(scenery, env);
        drawUnits();
        fx.render(modelBatch, env);
        modelBatch.end();

        drawHud();
    }

    private void step(float dt) {
        Controls c = controls;
        Battle.State before = battle.state;
        battle.update(dt, c.moveX, c.moveZ, c.aimX, c.aimZ, c.fire, c.dodge);
        handleEvents();
        if (battle.player.dodging() && MathUtils.randomBoolean(0.6f)) fx.trail(battle.player.x, battle.player.z);
        if (before == Battle.State.FIGHTING && battle.state != before) {
            controls.clear();
            selected = 1;
            menuDelay = 0.6f;
        }
    }

    /** Pause, upgrade picks and restarts from keyboard or controller. Taps go through tap(). */
    private void menus() {
        Controls c = controls;
        Battle.State st = battle.state;
        if (c.pause && st == Battle.State.FIGHTING) {
            paused = !paused;
            sticks.clear();
            return;
        }
        if (paused) {
            if (c.confirm) paused = false;
            return;
        }
        if (menuDelay > 0) return;
        if (st == Battle.State.UPGRADE) {
            int n = battle.offer.size;
            if (c.left) selected = (selected + n - 1) % n;
            if (c.right) selected = (selected + 1) % n;
            if (c.pick >= 1 && c.pick <= n) battle.choose(battle.offer.get(c.pick - 1));
            else if (c.confirm) battle.choose(battle.offer.get(selected));
        } else if (st != Battle.State.FIGHTING && c.confirm) {
            battle.reset();
        }
    }

    /** "Tap", "Press A" or "Press Enter", depending on what you're playing with. */
    private String confirmWord() {
        switch (controls.device) {
            case TOUCH: return "Tap";
            case CONTROLLER: return "Press A";
            case MOUSE: return "Click or press Enter";
            default: return "Press Enter";
        }
    }

    private String hint() {
        switch (controls.device) {
            case TOUCH: return "Left thumb moves. Right thumb aims and fires. ROLL dodges through bullets.";
            case CONTROLLER: return "Left stick moves. Right stick aims and fires. A rolls through bullets.";
            case MOUSE: return "WASD or arrows move. Mouse aims, click fires. Right-click or Shift rolls.";
            default: return "Arrows or WASD move. Space fires at the nearest enemy. Shift or X rolls.";
        }
    }

    /** Turns what happened in the battle this frame into things you see and hear. */
    private void handleEvents() {
        Battle.Soldier p = battle.player;
        for (Battle.Event e : battle.events) {
            switch (e.type) {
                case SHOT:
                    fx.muzzle(e.x, e.z, e.dx, e.dz, 1f);
                    sfx.play("rifle", 0.55f);
                    kickX -= e.dx * 0.12f;
                    kickZ -= e.dz * 0.12f;
                    break;
                case ENEMY_SHOT:
                    fx.muzzle(e.x, e.z, e.dx, e.dz, 0.7f);
                    sfx.playAt("enemy_rifle", 0.45f, e.x, e.z, p.x, p.z);
                    break;
                case TANK_AIM:
                    sfx.playAt("tank_aim", 0.8f, e.x, e.z, p.x, p.z);
                    break;
                case TANK_FIRE:
                    fx.bigMuzzle(e.x, e.z, e.dx, e.dz);
                    sfx.playAt("cannon", 1f, e.x, e.z, p.x, p.z);
                    shake = Math.max(shake, 0.45f);
                    break;
                case HIT:
                    fx.bulletHit(e.x, e.z, e.dx, e.dz);
                    sfx.playAt("hit", 0.5f, e.x, e.z, p.x, p.z);
                    break;
                case ARMOR_HIT:
                    fx.armorHit(e.x, e.z, e.dx, e.dz);
                    sfx.playAt("clank", 0.45f, e.x, e.z, p.x, p.z);
                    break;
                case KILL:
                    fx.kill(e.x, e.z, e.dx, e.dz);
                    sfx.playAt("hit", 0.8f, e.x, e.z, p.x, p.z);
                    freeze = Math.max(freeze, 0.025f);
                    break;
                case TANK_KILL:
                    fx.tankKill(e.x, e.z, e.dx, e.dz);
                    sfx.playAt("blast", 1f, e.x, e.z, p.x, p.z);
                    freeze = Math.max(freeze, 0.12f);
                    shake = Math.max(shake, 0.9f);
                    break;
                case IMPACT:
                    fx.impact(e.x, e.z, e.dx, e.dz);
                    break;
                case BLAST: {
                    fx.blast(e.x, e.z);
                    sfx.playAt("blast", 0.9f, e.x, e.z, p.x, p.z);
                    float dx = e.x - p.x, dz = e.z - p.z;
                    float near = MathUtils.clamp(1f - (float) Math.sqrt(dx * dx + dz * dz) / 14f, 0, 1);
                    shake = Math.max(shake, 0.3f + 0.6f * near);
                    break;
                }
                case PLAYER_HIT:
                    sfx.play("hurt", 0.8f);
                    shake = Math.max(shake, e.amount >= 10 ? 0.8f : 0.4f);
                    freeze = Math.max(freeze, 0.04f);
                    break;
                case DODGE:
                    fx.dodgeDust(e.x, e.z, e.dx, e.dz);
                    sfx.play("dodge", 0.7f);
                    break;
                case BREAKTHROUGH:
                    sfx.play("breakthrough", 0.6f, 0, 0);
                    lineFlash = 0.8f;
                    break;
            }
        }
    }

    private void drawUnits() {
        Battle.Soldier p = battle.player;
        ModelInstance body = p.hurtTimer > 0 ? playerHurtInst : playerInst;
        if (p.dodging()) {
            // Tuck and tip forward into the roll.
            float k = MathUtils.sin(MathUtils.PI * (1f - p.dodgeTime / Battle.DODGE_TIME));
            body.transform.setToTranslation(p.x, 0, p.z)
                    .rotate(p.dodgeZ, 0, -p.dodgeX, 70f * k)
                    .translate(0, 0.8f - 0.25f * k, 0)
                    .scale(1f, 1f - 0.35f * k, 1f);
            modelBatch.render(body, env);
        } else {
            body.transform.setToTranslation(p.x, 0.8f, p.z);
            modelBatch.render(body, env);
            ModelInstance gun = pooled(pGun, box, 0);
            gun.transform.setToTranslation(p.x + p.aimX * 0.5f, 1.1f, p.z + p.aimZ * 0.5f)
                    .rotate(Vector3.Y, yaw(p.aimX, p.aimZ)).scale(0.12f, 0.12f, 0.9f);
            modelBatch.render(gun, env);
        }

        int inf = 0, infHit = 0, tanks = 0, tankHits = 0, guns = 1, warns = 0;
        for (Battle.Enemy e : battle.enemies) {
            float a = yaw(e.faceX, e.faceZ);
            if (e.tank) {
                ModelInstance hull = e.hitTimer > 0 ? pooled(pTankHit, tankHit, tankHits++) : pooled(pHull, tankHull, tanks);
                // Hull points down the canyon, turret tracks you.
                hull.transform.setToTranslation(e.x, 0.5f, e.z).scale(2.2f, 1f, 2.8f);
                modelBatch.render(hull, env);
                ModelInstance turret = pooled(pTurret, tankTurret, tanks++);
                turret.transform.setToTranslation(e.x, 1.3f, e.z).rotate(Vector3.Y, a).scale(1.3f, 0.6f, 1.4f);
                modelBatch.render(turret, env);
                ModelInstance barrel = pooled(pGun, box, guns++);
                barrel.transform.setToTranslation(e.x + e.faceX * 1.4f, 1.35f, e.z + e.faceZ * 1.4f)
                        .rotate(Vector3.Y, a).scale(0.18f, 0.18f, 1.6f);
                modelBatch.render(barrel, env);

                if (e.windup > 0) {
                    // Warning line where the shell will go, widening and blinking faster as it's about to fire.
                    float k = 1f - e.windup / Battle.TANK_WINDUP;
                    boolean on = MathUtils.sin(k * k * 40f) > -0.3f;
                    if (on) {
                        float start = 1.6f, len = Battle.TANK_RANGE - start;
                        float wdt = 0.12f + 1.1f * k;
                        float cx = e.x + e.aimX * (start + len / 2), cz = e.z + e.aimZ * (start + len / 2);
                        ModelInstance line = pooled(pWarn, warn, warns);
                        line.transform.setToTranslation(cx, 0.04f, cz).rotate(Vector3.Y, yaw(e.aimX, e.aimZ)).scale(wdt, 0.02f, len);
                        modelBatch.render(line, env);
                        float r = Battle.BLAST_RADIUS * 2 * (0.3f + 0.7f * k);
                        ModelInstance disc = pooled(pWarnDisc, warnDisc, warns++);
                        disc.transform.setToTranslation(e.x + e.aimX * Battle.TANK_RANGE, 0.05f, e.z + e.aimZ * Battle.TANK_RANGE).scale(r, 0.02f, r);
                        modelBatch.render(disc, env);
                    }
                }
            } else {
                ModelInstance i = e.hitTimer > 0 ? pooled(pInfantryHit, krautHit, infHit++) : pooled(pInfantry, kraut, inf++);
                i.transform.setToTranslation(e.x, 0.8f, e.z);
                modelBatch.render(i, env);
                ModelInstance rifle = pooled(pGun, box, guns++);
                rifle.transform.setToTranslation(e.x + e.faceX * 0.45f, 1.1f, e.z + e.faceZ * 0.45f)
                        .rotate(Vector3.Y, a).scale(0.1f, 0.1f, 0.8f);
                modelBatch.render(rifle, env);
            }
        }

        int t = 0, et = 0, s = 0;
        for (Battle.Bullet b : battle.bullets) {
            ModelInstance i = b.shell ? pooled(pShell, shell, s++)
                    : b.enemy ? pooled(pEnemyTracer, enemyTracer, et++) : pooled(pTracer, tracer, t++);
            i.transform.setToTranslation(b.x, 1.1f, b.z);
            modelBatch.render(i, env);
        }
    }

    // ---- HUD ----------------------------------------------------------------

    private void text(String s, float scale, float x, float y, int align, Color c) {
        font.getData().setScale(scale * u);
        font.setColor(c);
        layout.setText(font, s);
        float dx = align == Align.center ? -layout.width / 2 : align == Align.right ? -layout.width : 0;
        font.draw(batch, s, x + dx, y);
    }

    private Rectangle cardRect(int i) {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float cw = w * 0.25f, ch = h * 0.42f, gap = w * 0.03f;
        return new Rectangle(w / 2 + (i - 1) * (cw + gap) - cw / 2, h * 0.2f, cw, ch);
    }

    private void bar(float x, float y, float w, float h, float frac, Color fill) {
        shapes.setColor(0, 0, 0, 0.5f);
        shapes.rect(x, y, w, h);
        shapes.setColor(fill);
        shapes.rect(x, y, w * MathUtils.clamp(frac, 0, 1), h);
    }

    private void drawHud() {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        Battle b = battle;
        Battle.Soldier p = b.player;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(hudMatrix);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        if (p.hurtTimer > 0) { shapes.setColor(0.8f, 0, 0, p.hurtTimer * 0.8f); shapes.rect(0, 0, w, h); }

        float pad = 12 * u;
        bar(pad, h - pad - 50 * u, 110 * u, 8 * u, p.hp / p.maxHp, new Color(0.75f, 0.2f, 0.15f, 1));
        boolean blink = lineFlash > 0 && MathUtils.sin(lineFlash * 30f) > 0;
        bar(pad, h - pad - 70 * u, 110 * u, 8 * u, b.line / 100f, blink ? Color.SCARLET : new Color(0.35f, 0.55f, 0.85f, 1));

        if (controls.showTouch() && b.state == Battle.State.FIGHTING) {
            for (Sticks.Stick s : new Sticks.Stick[] { sticks.move, sticks.aim }) {
                if (!s.active()) continue;
                shapes.setColor(1, 1, 1, 0.15f);
                shapes.circle(s.originX, s.originY, sticks.radius, 32);
                shapes.setColor(1, 1, 1, 0.45f);
                shapes.circle(s.knobX, s.knobY, sticks.radius * 0.4f, 24);
            }
            // Dodge button. Fills back up as the cooldown runs out.
            float ready = 1f - p.dodgeCooldown / Battle.DODGE_COOLDOWN;
            shapes.setColor(0, 0, 0, 0.35f);
            shapes.circle(sticks.dodgeX, sticks.dodgeY, sticks.dodgeR, 32);
            shapes.setColor(1, 1, 1, ready >= 1 ? 0.5f : 0.2f);
            shapes.circle(sticks.dodgeX, sticks.dodgeY, sticks.dodgeR * ready, 32);
        }

        boolean overlay = b.state != Battle.State.FIGHTING || paused;
        if (overlay) { shapes.setColor(0, 0, 0, 0.6f); shapes.rect(0, 0, w, h); }
        if (b.state == Battle.State.UPGRADE && !paused) {
            for (int i = 0; i < b.offer.size; i++) {
                Rectangle r = cardRect(i);
                if (i == selected && !controls.showTouch()) {
                    float e = 4 * u;
                    shapes.setColor(Color.GOLD);
                    shapes.rect(r.x - e, r.y - e, r.width + 2 * e, r.height + 2 * e);
                }
                shapes.setColor(0.25f, 0.27f, 0.15f, 0.95f);
                shapes.rect(r.x, r.y, r.width, r.height);
            }
        }
        shapes.end();

        batch.setProjectionMatrix(hudMatrix);
        batch.begin();
        int secs = (int) Math.ceil(Math.max(0, b.clock));
        text(String.format("%d:%02d", secs / 60, secs % 60), 2.6f, w / 2, h - pad, Align.center,
                secs <= 10 ? Color.SCARLET : Color.WHITE);
        text(Battle.DAYS[b.day], 1f, pad, h - pad, Align.left, Color.WHITE);
        text("Health", 0.8f, pad + 116 * u, h - pad - 40 * u, Align.left, Color.WHITE);
        text("The line", 0.8f, pad + 116 * u, h - pad - 60 * u, Align.left, Color.WHITE);
        text("Kills " + b.kills, 1f, w - pad, h - pad, Align.right, Color.WHITE);
        if (controls.showTouch() && b.state == Battle.State.FIGHTING && !paused)
            text("ROLL", 0.8f, sticks.dodgeX, sticks.dodgeY + 5 * u, Align.center, Color.WHITE);
        if (b.state == Battle.State.FIGHTING && b.clock > Battle.DAY_LENGTH - 6 && b.day == 0)
            text(hint(), 0.9f, w / 2, 30 * u, Align.center, Color.WHITE);

        if (paused) {
            text("Paused", 2.4f, w / 2, h * 0.6f, Align.center, Color.WHITE);
            text(confirmWord() + " to resume", 1.1f, w / 2, h * 0.45f, Align.center, Color.LIGHT_GRAY);
        } else switch (b.state) {
            case UPGRADE:
                text("You held. Pick one before " + Battle.DAYS[b.day + 1].split(" - ")[0] + ".", 1.4f, w / 2, h * 0.85f, Align.center, Color.WHITE);
                if (!controls.showTouch())
                    text(controls.device == Controls.Device.CONTROLLER ? "Left and right to choose, A to pick" : "Arrows to choose, Enter to pick, or press 1, 2, 3",
                            0.9f, w / 2, h * 0.12f, Align.center, Color.LIGHT_GRAY);
                for (int i = 0; i < b.offer.size; i++) {
                    Rectangle r = cardRect(i);
                    Battle.Upgrade up = b.offer.get(i);
                    text(up.title, 1.3f, r.x + r.width / 2, r.y + r.height - 16 * u, Align.center, Color.GOLD);
                    font.getData().setScale(0.95f * u);
                    font.setColor(Color.WHITE);
                    font.draw(batch, up.text, r.x + 10 * u, r.y + r.height * 0.6f, r.width - 20 * u, Align.center, true);
                }
                break;
            case DEAD:
                endScreen("You were killed.", "Day " + (b.day + 1) + " of " + Battle.DAYS.length + ", " + b.kills + " kills");
                break;
            case LINE_BROKEN:
                endScreen("The line broke.", "Too many got past on " + Battle.DAYS[b.day]);
                break;
            case WON:
                endScreen("The pass held.", "Feb 22, 1943: Rommel calls off the attack. " + b.kills + " kills");
                break;
            default:
        }
        batch.end();
    }

    private void endScreen(String title, String sub) {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        text(title, 2.4f, w / 2, h * 0.62f, Align.center, Color.WHITE);
        text(sub, 1.1f, w / 2, h * 0.48f, Align.center, Color.LIGHT_GRAY);
        text(confirmWord() + " to try again", 1f, w / 2, h * 0.32f, Align.center, Color.GOLD);
    }

    /** Taps on menus and overlays. Returns false during play so the sticks get the touch. */
    private boolean tap(int sx, int sy) {
        if (paused) { paused = false; return true; }
        if (battle.state != Battle.State.FIGHTING && menuDelay > 0) return true;
        float y = Gdx.graphics.getHeight() - sy;
        switch (battle.state) {
            case FIGHTING: return false;
            case UPGRADE:
                for (int i = 0; i < battle.offer.size; i++)
                    if (cardRect(i).contains(sx, y)) { battle.choose(battle.offer.get(i)); return true; }
                return true;
            default:
                battle.reset();
                return true;
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();
        hudMatrix.setToOrtho2D(0, 0, width, height);
        u = height / 360f;
        sticks.resize(width, height);
    }

    @Override
    public void pause() {
        if (battle.state == Battle.State.FIGHTING) paused = true;
        controls.clear();
    }

    @Override
    public void dispose() {
        for (Model m : models) m.dispose();
        fx.dispose();
        sfx.dispose();
        modelBatch.dispose();
        batch.dispose();
        shapes.dispose();
        font.dispose();
    }
}
