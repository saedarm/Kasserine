package com.saedarm.kasserine;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * Visual-only effects: particles, fallen soldiers, burning tank wrecks, scorch
 * marks. None of this affects the rules. It's all thrown away when a day ends.
 */
public class Fx implements Disposable {

    private static final long ATTR = Usage.Position | Usage.Normal;
    private static final int MAX_PARTICLES = 500;

    /** Each kind gets its own model so we never change materials per instance. */
    public enum Kind { DUST, SMOKE, FIRE, FLASH, SPARK, BLOOD, SCORCH, MARKER }

    private static class Particle {
        Kind kind;
        float x, y, z, vx, vy, vz;
        float life, maxLife, size0, size1, gravity, drag;
    }

    private static class Corpse {
        float x, z, dx, dz, t;
    }

    private static class Wreck {
        float x, z, faceX, faceZ, t, smokeTimer;
    }

    private final Array<Particle> particles = new Array<>(false, MAX_PARTICLES);
    private final Array<Particle> spare = new Array<>(false, MAX_PARTICLES);
    private final Array<Corpse> corpses = new Array<>();
    private final Array<Wreck> wrecks = new Array<>();

    private final Model[] kindModels = new Model[Kind.values().length];
    private final Array<Array<ModelInstance>> kindPools = new Array<>();
    private final Model corpseModel, wreckHull, wreckTurret, wreckBarrel;
    private final Array<ModelInstance> corpsePool = new Array<>(), wreckPool = new Array<>();
    private final int[] used = new int[Kind.values().length];

    public Fx() {
        ModelBuilder mb = new ModelBuilder();
        for (Kind k : Kind.values()) {
            Model m;
            switch (k) {
                case DUST: m = mb.createBox(1, 1, 1, see("#b8956a", 0.6f), ATTR); break;
                case SMOKE: m = mb.createBox(1, 1, 1, see("#4a4440", 0.5f), ATTR); break;
                case FIRE: m = mb.createBox(1, 1, 1, mat("#ff7a1a", true), ATTR); break;
                case FLASH: m = mb.createSphere(1, 1, 1, 8, 8, mat("#ffe9a0", true), ATTR); break;
                case SPARK: m = mb.createBox(1, 1, 1, mat("#fff2b0", true), ATTR); break;
                case BLOOD: m = mb.createBox(1, 1, 1, mat("#5a1410", false), ATTR); break;
                case MARKER: m = mb.createBox(1, 1, 1, see("#58b048", 0.55f), ATTR); break;
                default: m = mb.createCylinder(1, 1, 1, 14, see("#2b2218", 0.55f), ATTR); break; // SCORCH
            }
            kindModels[k.ordinal()] = m;
            kindPools.add(new Array<>());
        }
        corpseModel = mb.createCylinder(0.7f, 1.6f, 0.7f, 12, mat("#8a7858", false), ATTR); // dusty, so bodies read differently from live troops
        wreckHull = mb.createBox(1, 1, 1, mat("#2e2a22", false), ATTR);
        wreckTurret = mb.createBox(1, 1, 1, mat("#26231d", false), ATTR);
        wreckBarrel = mb.createBox(1, 1, 1, mat("#1e1c18", false), ATTR);
    }

    private static Material mat(String hex, boolean emissive) {
        Color c = Color.valueOf(hex);
        return emissive
                ? new Material(ColorAttribute.createDiffuse(c), ColorAttribute.createEmissive(c))
                : new Material(ColorAttribute.createDiffuse(c));
    }

    /** See-through, so piles of smoke and dust don't hide the fight. */
    private static Material see(String hex, float opacity) {
        return new Material(ColorAttribute.createDiffuse(Color.valueOf(hex)), new BlendingAttribute(opacity));
    }

    public void clear() {
        spare.addAll(particles);
        particles.clear();
        corpses.clear();
        wrecks.clear();
    }

    // ---- spawning -----------------------------------------------------------

    private Particle add(Kind kind, float x, float y, float z, float life, float size0, float size1) {
        Particle p;
        if (particles.size >= MAX_PARTICLES) p = particles.removeIndex(0);
        else p = spare.size > 0 ? spare.pop() : new Particle();
        p.kind = kind;
        p.x = x; p.y = y; p.z = z;
        p.vx = p.vy = p.vz = 0;
        p.life = p.maxLife = life;
        p.size0 = size0; p.size1 = size1;
        p.gravity = 0; p.drag = 0;
        particles.add(p);
        return p;
    }

    /** A spray of particles in a cone around direction (dx, dz). */
    private void spray(Kind kind, int n, float x, float y, float z, float dx, float dz,
                       float speed, float cone, float up, float life, float size0, float size1, float gravity) {
        float base = MathUtils.atan2(dz, dx);
        for (int i = 0; i < n; i++) {
            float a = base + MathUtils.random(-cone, cone);
            float sp = speed * MathUtils.random(0.4f, 1f);
            Particle p = add(kind, x, y, z, life * MathUtils.random(0.6f, 1f), size0, size1);
            p.vx = MathUtils.cos(a) * sp;
            p.vz = MathUtils.sin(a) * sp;
            p.vy = up * MathUtils.random(0.5f, 1f);
            p.gravity = gravity;
            p.drag = 3f;
        }
    }

    public void muzzle(float x, float z, float dx, float dz, float scale) {
        add(Kind.FLASH, x, 1.1f, z, 0.05f, 0.55f * scale, 0.2f * scale);
        spray(Kind.SPARK, 2, x, 1.1f, z, dx, dz, 10f, 0.25f, 1f, 0.08f, 0.07f * scale, 0.02f, 0);
        Particle s = add(Kind.SMOKE, x + dx * 0.3f, 1.1f, z + dz * 0.3f, 0.4f, 0.15f * scale, 0.45f * scale);
        s.vx = dx * 1.5f; s.vz = dz * 1.5f; s.vy = 0.8f; s.drag = 2f;
    }

    public void bigMuzzle(float x, float z, float dx, float dz) {
        add(Kind.FLASH, x, 1.35f, z, 0.09f, 1.6f, 0.6f);
        spray(Kind.FIRE, 6, x, 1.35f, z, dx, dz, 9f, 0.35f, 1f, 0.15f, 0.35f, 0.05f, 0);
        spray(Kind.SMOKE, 8, x, 1.2f, z, dx, dz, 3f, 0.9f, 1.2f, 1.1f, 0.4f, 1.3f, -0.5f);
        spray(Kind.DUST, 10, x - dx * 1.5f, 0.2f, z - dz * 1.5f, -dx, -dz, 5f, 1.6f, 1f, 0.8f, 0.35f, 0.9f, 0);
    }

    public void bulletHit(float x, float z, float dx, float dz) {
        spray(Kind.BLOOD, 5, x, 1.0f, z, dx, dz, 4f, 0.7f, 2f, 0.35f, 0.14f, 0.04f, 12f);
        spray(Kind.DUST, 2, x, 0.9f, z, dx, dz, 2f, 1.2f, 1f, 0.3f, 0.15f, 0.35f, 0);
    }

    public void armorHit(float x, float z, float dx, float dz) {
        spray(Kind.SPARK, 7, x - dx * 1f, 1.0f, z - dz * 1f, -dx, -dz, 9f, 1.1f, 3f, 0.2f, 0.09f, 0.02f, 15f);
    }

    public void impact(float x, float z, float dx, float dz) {
        spray(Kind.DUST, 4, x, 0.8f, z, dx, dz, 3f, 0.9f, 1.5f, 0.45f, 0.15f, 0.4f, 0);
        spray(Kind.SPARK, 2, x, 0.8f, z, dx, dz, 6f, 0.8f, 2f, 0.1f, 0.06f, 0.02f, 10f);
    }

    public void blast(float x, float z) { blast(x, z, 1f); }

    /** scale 1 = a tank shell. Grenades are a bit smaller, bombs bigger. */
    public void blast(float x, float z, float scale) {
        float k = scale;
        float n = Math.min(1f, k); // bigger blasts spread further but don't spawn more pieces
        add(Kind.FLASH, x, 0.8f, z, 0.12f, 3.2f * k, 1.2f * k);
        spray(Kind.FIRE, (int) (16 * n), x, 0.5f, z, 1, 0, 7f * k, MathUtils.PI, 4f, 0.35f, 0.5f * k, 0.1f, 4f);
        spray(Kind.SMOKE, (int) (10 * n), x, 0.6f, z, 1, 0, 2.5f * k, MathUtils.PI, 2.5f, 1.6f, 0.45f, 1.4f, -0.6f);
        spray(Kind.DUST, (int) (14 * n), x, 0.2f, z, 1, 0, 8f * k, MathUtils.PI, 1.5f, 1.0f, 0.35f, 0.9f, 2f);
        spray(Kind.SPARK, (int) (8 * n), x, 0.6f, z, 1, 0, 10f * k, MathUtils.PI, 7f, 0.5f, 0.1f, 0.03f, 18f);
        Particle s = add(Kind.SCORCH, x, 0.01f, z, 15f, 1.9f * k, 1.9f * k);
        s.y = 0.01f;
    }

    /** Green smoke rising from a supply crate, so you can spot it. */
    public void marker(float x, float z) {
        Particle p = add(Kind.MARKER, x + MathUtils.random(-0.15f, 0.15f), 0.4f, z + MathUtils.random(-0.15f, 0.15f), 2.2f, 0.25f, 1.1f);
        p.vy = 1.8f; p.vx = 0.5f; p.drag = 0.4f;
    }

    public void dodgeDust(float x, float z, float dx, float dz) {
        spray(Kind.DUST, 7, x, 0.15f, z, -dx, -dz, 3f, 1.0f, 0.8f, 0.6f, 0.25f, 0.6f, 0);
    }

    public void trail(float x, float z) {
        Particle p = add(Kind.DUST, x + MathUtils.random(-0.2f, 0.2f), 0.15f, z + MathUtils.random(-0.2f, 0.2f), 0.4f, 0.25f, 0.05f);
        p.vy = 0.5f;
    }

    public void kill(float x, float z, float dx, float dz) {
        Corpse c = new Corpse();
        c.x = x; c.z = z; c.dx = dx; c.dz = dz;
        corpses.add(c);
        if (corpses.size > 80) corpses.removeIndex(0);
        spray(Kind.BLOOD, 9, x, 1.0f, z, dx, dz, 5f, 0.6f, 2.5f, 0.45f, 0.16f, 0.05f, 12f);
        spray(Kind.DUST, 6, x + dx, 0.15f, z + dz, dx, dz, 2f, 1.4f, 0.6f, 0.8f, 0.3f, 0.7f, 0);
    }

    public void tankKill(float x, float z, float faceX, float faceZ) {
        Wreck w = new Wreck();
        w.x = x; w.z = z; w.faceX = faceX; w.faceZ = faceZ;
        wrecks.add(w);
        blast(x, z);
        blast(x + MathUtils.random(-0.6f, 0.6f), z + MathUtils.random(-0.6f, 0.6f));
    }

    // ---- update and draw ----------------------------------------------------

    public void update(float dt) {
        for (int i = particles.size - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) { spare.add(particles.removeIndex(i)); continue; }
            float damp = 1f / (1f + p.drag * dt);
            p.vx *= damp; p.vz *= damp;
            p.vy -= p.gravity * dt;
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
            if (p.y < 0.03f && p.kind != Kind.SCORCH) { p.y = 0.03f; p.vy = 0; }
        }
        for (Corpse c : corpses) c.t += dt;
        for (Wreck w : wrecks) {
            w.t += dt;
            w.smokeTimer -= dt;
            if (w.smokeTimer <= 0) {
                w.smokeTimer = 0.12f;
                Particle s = add(Kind.SMOKE, w.x + MathUtils.random(-0.5f, 0.5f), 1.4f, w.z + MathUtils.random(-0.5f, 0.5f), 2.2f, 0.4f, 1.6f);
                s.vy = 1.6f; s.vx = 0.4f; s.drag = 0.3f;
                if (w.t < 12f) {
                    Particle f = add(Kind.FIRE, w.x + MathUtils.random(-0.6f, 0.6f), 1.2f, w.z + MathUtils.random(-0.6f, 0.6f), 0.35f, 0.4f, 0.05f);
                    f.vy = 2f;
                }
            }
        }
    }

    private ModelInstance pooled(Array<ModelInstance> pool, Model m, int i) {
        while (pool.size <= i) pool.add(new ModelInstance(m));
        return pool.get(i);
    }

    public void render(ModelBatch batch, Environment env) {
        // Fallen soldiers: tip over away from the shot in 0.25s, then lie there.
        for (int i = 0; i < corpses.size; i++) {
            Corpse c = corpses.get(i);
            float k = Math.min(1f, c.t / 0.25f);
            float ang = 90f * k * k;
            // Rotating about (up x fallDir) tips the top toward the fall direction.
            ModelInstance m = pooled(corpsePool, corpseModel, i);
            float lift = 0.35f * MathUtils.sinDeg(ang);
            m.transform.setToTranslation(c.x + c.dx * 0.3f * k, lift, c.z + c.dz * 0.3f * k)
                    .rotate(c.dz, 0, -c.dx, ang)
                    .translate(0, 0.8f, 0);
            batch.render(m, env);
        }

        int w = 0;
        for (Wreck r : wrecks) {
            float yaw = MathUtils.atan2(r.faceX, r.faceZ) * MathUtils.radiansToDegrees;
            ModelInstance hull = pooled(wreckPool, wreckHull, w++);
            hull.transform.setToTranslation(r.x, 0.45f, r.z).scale(2.2f, 0.9f, 2.8f);
            batch.render(hull, env);
            ModelInstance turret = pooled(wreckPool, wreckTurret, w++);
            // The turret got knocked askew in the explosion.
            turret.transform.setToTranslation(r.x + 0.2f, 1.15f, r.z).rotate(0, 1, 0, yaw + 25f).rotate(1, 0, 0, 8f).scale(1.3f, 0.6f, 1.4f);
            batch.render(turret, env);
            ModelInstance barrel = pooled(wreckPool, wreckBarrel, w++);
            barrel.transform.setToTranslation(r.x + 0.2f + r.faceX * 1.1f, 1.0f, r.z + r.faceZ * 1.1f)
                    .rotate(0, 1, 0, yaw + 25f).rotate(1, 0, 0, 14f).scale(0.18f, 0.18f, 1.6f);
            batch.render(barrel, env);
        }

        java.util.Arrays.fill(used, 0);
        for (Particle p : particles) {
            int k = p.kind.ordinal();
            float t = 1f - p.life / p.maxLife;
            float s = MathUtils.lerp(p.size0, p.size1, t);
            ModelInstance m = pooled(kindPools.get(k), kindModels[k], used[k]++);
            if (p.kind == Kind.SCORCH) {
                // Scorch marks fade by sinking into the sand over their last few seconds.
                float sink = p.life < 4f ? (4f - p.life) / 4f * 0.05f : 0;
                m.transform.setToTranslation(p.x, 0.005f - sink, p.z).scale(s, 0.02f, s);
            } else {
                m.transform.setToTranslation(p.x, p.y, p.z).rotate(0, 1, 0, p.x * 90f + p.z * 50f).scale(s, s, s);
            }
            batch.render(m, env);
        }
    }

    @Override
    public void dispose() {
        for (Model m : kindModels) m.dispose();
        corpseModel.dispose();
        wreckHull.dispose();
        wreckTurret.dispose();
        wreckBarrel.dispose();
    }
}
