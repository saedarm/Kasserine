package com.saedarm.kasserine;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;

/**
 * All the rules, no drawing. The canyon runs along +z; the Germans come from
 * the far end and you hold the south mouth. x is across the canyon.
 *
 * Battle never touches graphics or sound. Anything the player should see or
 * hear goes into {@link #events}, and the renderer turns those into effects.
 */
public class Battle {

    // ---- the campaign: one day per run stage --------------------------------

    public static final String[] DAYS = {
        "Feb 14, 1943 - Sidi Bou Zid",
        "Feb 17, 1943 - Sbeitla",
        "Feb 19, 1943 - Kasserine Pass",
        "Feb 20, 1943 - Kasserine Pass",
        "Feb 21, 1943 - Thala",
    };
    public static final float DAY_LENGTH = 60f;   // seconds you have to hold
    public static final float LENGTH = 64f;       // canyon length
    public static final float SPAWN_Z = LENGTH - 3f;

    // Dodge roll
    public static final float DODGE_TIME = 0.28f;     // seconds of roll, you can't be hit during it
    public static final float DODGE_SPEED = 15f;
    public static final float DODGE_COOLDOWN = 0.9f;  // from the start of one roll to the next

    // Tanks
    public static final float TANK_RANGE = 16f;
    public static final float TANK_WINDUP = 0.9f;     // how long the warning line shows before it fires
    public static final float SHELL_SPEED = 16f;
    public static final float BLAST_RADIUS = 1.8f;

    // ---- upgrades: the lessons the Army learned the hard way ----------------

    public enum Upgrade {
        THOMPSON("Thompson", "+1 bullet per shot, fanned out"),
        GARAND("M1 Garand", "+30% bullet damage"),
        QUICK_HANDS("Quick Hands", "+25% rate of fire"),
        FIELD_DRESSING("Field Dressing", "+25 max health and a full heal"),
        JUMP_BOOTS("Jump Boots", "+15% move speed"),
        BAZOOKA("Bazooka Team", "Bullets do triple damage to tanks"),
        AP_ROUNDS("AP Rounds", "Bullets punch through one more enemy"),
        AIR_COVER("Air Cover", "The line gets +30 strength back");

        public final String title, text;
        Upgrade(String title, String text) { this.title = title; this.text = text; }
    }

    // ---- events for the renderer --------------------------------------------

    public enum Ev {
        SHOT,          // you fired. x,z = muzzle, dx,dz = aim
        ENEMY_SHOT,    // a rifleman fired
        TANK_AIM,      // a tank started its windup
        TANK_FIRE,     // a tank fired its shell
        HIT,           // your bullet hit infantry. dx,dz = bullet direction
        ARMOR_HIT,     // your bullet hit a tank
        KILL,          // infantry died. dx,dz = which way they fall
        TANK_KILL,     // a tank died. dx,dz = its facing
        IMPACT,        // a bullet hit a rock
        BLAST,         // a shell exploded
        PLAYER_HIT,    // you took damage. amount = damage
        DODGE,         // you started a roll. dx,dz = roll direction
        BREAKTHROUGH,  // someone got past you and hurt the line. amount = line lost
    }

    public static class Event {
        public Ev type;
        public float x, z, dx, dz, amount;
    }

    /** Filled during update(), cleared at the start of the next one. */
    public final Array<Event> events = new Array<>();

    private void emit(Ev type, float x, float z, float dx, float dz, float amount) {
        Event e = new Event();
        e.type = type; e.x = x; e.z = z; e.dx = dx; e.dz = dz; e.amount = amount;
        events.add(e);
    }

    // ---- entities -----------------------------------------------------------

    /** Anything that stands in the canyon. */
    public static class Body { public float x, z; }

    public static class Soldier extends Body {
        public float aimX = 0, aimZ = 1, moveX = 0, moveZ = 1;
        public float hp, maxHp = 100, speed = 5f;
        public float fireRate = 5f, damage = 10f, cooldown;
        public int pellets = 1, pierce = 0;
        public boolean bazooka;
        public float hurtTimer;
        public float dodgeTime, dodgeCooldown, dodgeX, dodgeZ;

        public boolean dodging() { return dodgeTime > 0; }
    }

    public static class Enemy extends Body {
        public boolean tank;
        public float hp, speed, radius, cooldown, hitTimer, stagger;
        public float faceX = 0, faceZ = -1;
        /** Tanks only: counts down while the tank is about to fire along aimX/aimZ. */
        public float windup, aimX, aimZ;
    }

    public static class Bullet {
        public float x, z, vx, vz, life, damage;
        public boolean enemy, shell;
        public int pierce;
        public Enemy lastHit;
    }

    public static class Rock {
        public final float x, z, r;
        Rock(float x, float z, float r) { this.x = x; this.z = z; this.r = r; }
    }

    public enum State { FIGHTING, UPGRADE, DEAD, LINE_BROKEN, WON }

    // ---- state --------------------------------------------------------------

    public final Soldier player = new Soldier();
    public final Array<Enemy> enemies = new Array<>();
    public final Array<Bullet> bullets = new Array<>();
    public final Array<Rock> rocks = new Array<>();
    public final Array<Upgrade> offer = new Array<>();

    public State state;
    public int day;          // 0-based
    public float clock;      // seconds left in the day
    public float line;       // 0..100, enemies that slip past knock it down
    public int kills;
    /** Goes up by one every time a day starts, so the renderer knows to clear old effects. */
    public int dayCounter;

    private float spawnTimer;

    public Battle() {
        buildRocks();
        reset();
    }

    public void reset() {
        Soldier p = player;
        p.x = 0; p.z = 6; p.maxHp = 100; p.hp = 100; p.speed = 5f;
        p.fireRate = 5f; p.damage = 10f; p.pellets = 1; p.pierce = 0; p.bazooka = false;
        day = 0; line = 100; kills = 0;
        startDay();
    }

    private void startDay() {
        enemies.clear();
        bullets.clear();
        clock = DAY_LENGTH;
        spawnTimer = 1.5f;
        Soldier p = player;
        p.z = 6; p.x = 0;
        p.dodgeTime = 0; p.dodgeCooldown = 0; p.hurtTimer = 0;
        state = State.FIGHTING;
        dayCounter++;
    }

    /** Half the canyon's width at depth z. Wiggles so it reads like rock, not a hallway. */
    public static float halfWidth(float z) {
        return 8f + 1.8f * MathUtils.sin(z * 0.15f) + 1.2f * MathUtils.sin(z * 0.41f + 1f);
    }

    private void buildRocks() {
        // Fixed seed so the canyon is the same every run.
        java.util.Random r = new java.util.Random(1943);
        for (int i = 0; i < 18; i++) {
            float z = 12 + r.nextFloat() * (LENGTH - 18);
            float hw = halfWidth(z) - 1.5f;
            float x = (r.nextFloat() * 2 - 1) * hw;
            rocks.add(new Rock(x, z, 0.7f + r.nextFloat() * 1.1f));
        }
    }

    // ---- per-frame update ---------------------------------------------------

    /**
     * move/aim are -1..1 stick values. fire = trigger held.
     * dodge = the dodge button was pressed this frame.
     */
    public void update(float dt, float moveX, float moveZ, float aimX, float aimZ, boolean fire, boolean dodge) {
        events.clear();
        if (state != State.FIGHTING) return;

        clock -= dt;
        if (clock <= 0) { endDay(); return; }

        updatePlayer(dt, moveX, moveZ, aimX, aimZ, fire, dodge);
        spawnEnemies(dt);
        updateEnemies(dt);
        updateBullets(dt);

        if (player.hp <= 0) state = State.DEAD;
        else if (line <= 0) state = State.LINE_BROKEN;
    }

    private void updatePlayer(float dt, float mx, float mz, float ax, float az, boolean fire, boolean dodge) {
        Soldier p = player;
        p.hurtTimer = Math.max(0, p.hurtTimer - dt);
        p.dodgeCooldown = Math.max(0, p.dodgeCooldown - dt);

        float len = (float) Math.sqrt(mx * mx + mz * mz);
        if (len > 1) { mx /= len; mz /= len; len = 1; }
        if (len > 0.2f) { p.moveX = mx / len; p.moveZ = mz / len; }

        float alen = (float) Math.sqrt(ax * ax + az * az);
        if (alen > 0.2f) { p.aimX = ax / alen; p.aimZ = az / alen; }

        // Roll the way you're moving. Standing still, roll the way you're facing.
        if (dodge && p.dodgeCooldown <= 0 && !p.dodging()) {
            if (len > 0.2f) { p.dodgeX = p.moveX; p.dodgeZ = p.moveZ; }
            else { p.dodgeX = p.aimX; p.dodgeZ = p.aimZ; }
            p.dodgeTime = DODGE_TIME;
            p.dodgeCooldown = DODGE_COOLDOWN;
            emit(Ev.DODGE, p.x, p.z, p.dodgeX, p.dodgeZ, 0);
        }

        if (p.dodging()) {
            p.dodgeTime -= dt;
            // Fast at the start, easing off at the end.
            float t = Math.max(0, p.dodgeTime) / DODGE_TIME;
            float sp = DODGE_SPEED * (0.4f + 0.6f * t);
            p.x += p.dodgeX * sp * dt;
            p.z += p.dodgeZ * sp * dt;
        } else {
            p.x += mx * p.speed * dt;
            p.z += mz * p.speed * dt;
        }
        p.z = MathUtils.clamp(p.z, 1f, LENGTH - 2f);
        float hw = halfWidth(p.z) - 0.4f;
        p.x = MathUtils.clamp(p.x, -hw, hw);
        pushOutOfRocks(p, 0.4f);

        p.cooldown -= dt;
        if (fire && !p.dodging() && p.cooldown <= 0) {
            p.cooldown = 1f / p.fireRate;
            float base = MathUtils.atan2(p.aimZ, p.aimX);
            float spread = 0.14f;
            for (int i = 0; i < p.pellets; i++) {
                float a = base + (i - (p.pellets - 1) / 2f) * spread + MathUtils.random(-0.03f, 0.03f);
                Bullet b = new Bullet();
                b.x = p.x + MathUtils.cos(a) * 0.5f;
                b.z = p.z + MathUtils.sin(a) * 0.5f;
                b.vx = MathUtils.cos(a) * 26f;
                b.vz = MathUtils.sin(a) * 26f;
                b.life = 0.75f;
                b.damage = p.damage;
                b.pierce = p.pierce;
                bullets.add(b);
            }
            emit(Ev.SHOT, p.x + p.aimX * 0.9f, p.z + p.aimZ * 0.9f, p.aimX, p.aimZ, 0);
        }
    }

    private void spawnEnemies(float dt) {
        spawnTimer -= dt;
        if (spawnTimer > 0) return;
        float elapsed = DAY_LENGTH - clock;
        // Gets busier through the day and across days.
        spawnTimer = MathUtils.random(0.6f, 1.4f) * (1.6f - day * 0.2f) * (1f - elapsed / DAY_LENGTH * 0.4f);

        int count = 1 + MathUtils.random(day / 2 + 1);
        for (int i = 0; i < count; i++) {
            Enemy e = new Enemy();
            e.tank = day >= 2 && MathUtils.randomBoolean(0.06f + day * 0.03f);
            // Just off the top of the screen, so the fight comes to you.
            e.z = Math.min(SPAWN_Z, player.z + 24f) + MathUtils.random(-1.5f, 1.5f);
            e.x = MathUtils.random(-1f, 1f) * (halfWidth(e.z) - 1.5f);
            if (e.tank) { e.hp = 140; e.speed = 1.3f; e.radius = 1.2f; }
            else { e.hp = 20 + day * 4; e.speed = MathUtils.random(2.0f, 2.8f); e.radius = 0.4f; }
            e.cooldown = MathUtils.random(1.5f, 3f);
            enemies.add(e);
        }
    }

    private void updateEnemies(float dt) {
        Soldier p = player;
        for (int i = enemies.size - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            e.hitTimer = Math.max(0, e.hitTimer - dt);
            e.stagger = Math.max(0, e.stagger - dt);

            float dx = p.x - e.x, dz = p.z - e.z;
            float dist = Math.max(0.01f, (float) Math.sqrt(dx * dx + dz * dz));

            // Go for you if you're close, otherwise push down the canyon past you.
            float tx, tz;
            if (dist < 14f) { tx = dx / dist; tz = dz / dist; }
            else { tx = -e.x * 0.05f; tz = -1f; }

            // Infantry stop and shoot at mid range instead of hugging you.
            // Tanks stop while they aim. Anyone just shot staggers for a moment.
            float moveScale = 1f;
            if (!e.tank && dist < 6f) moveScale = 0f;
            if (e.windup > 0 || e.stagger > 0) moveScale = 0f;

            // Don't stack on each other.
            float sx = 0, sz = 0;
            for (int j = 0; j < enemies.size; j++) {
                Enemy o = enemies.get(j);
                if (o == e) continue;
                float ox = e.x - o.x, oz = e.z - o.z;
                float min = e.radius + o.radius + 0.3f;
                float d2 = ox * ox + oz * oz;
                if (d2 < min * min && d2 > 0.0001f) {
                    float d = (float) Math.sqrt(d2);
                    sx += ox / d * (min - d);
                    sz += oz / d * (min - d);
                }
            }

            e.x += (tx * e.speed * moveScale) * dt + sx * 4f * dt;
            e.z += (tz * e.speed * moveScale) * dt + sz * 4f * dt;
            float hw = halfWidth(e.z) - e.radius;
            e.x = MathUtils.clamp(e.x, -hw, hw);
            pushOutOfRocks(e, e.radius);

            if (e.windup > 0) { e.faceX = e.aimX; e.faceZ = e.aimZ; }
            else { e.faceX = dx / dist; e.faceZ = dz / dist; }

            // Slipped past you out the south end.
            if (e.z < 0.5f) {
                float lost = e.tank ? 25 : 8;
                line -= lost;
                emit(Ev.BREAKTHROUGH, e.x, e.z, 0, -1, lost);
                enemies.removeIndex(i);
                continue;
            }

            if (e.tank) updateTankGun(e, dt, dist, dx, dz);
            else updateRifle(e, dt, dist, dx, dz);
        }
    }

    private void updateRifle(Enemy e, float dt, float dist, float dx, float dz) {
        e.cooldown -= dt;
        if (dist > 11f || e.cooldown > 0 || e.stagger > 0) return;
        e.cooldown = MathUtils.random(2.4f, 3.6f);
        float a = MathUtils.atan2(dz, dx) + MathUtils.random(-0.08f, 0.08f);
        Bullet b = new Bullet();
        b.enemy = true;
        b.x = e.x; b.z = e.z;
        b.vx = MathUtils.cos(a) * 9f;
        b.vz = MathUtils.sin(a) * 9f;
        b.life = 11f / 9f + 0.3f;
        b.damage = 5;
        bullets.add(b);
        emit(Ev.ENEMY_SHOT, e.x + MathUtils.cos(a) * 0.6f, e.z + MathUtils.sin(a) * 0.6f, MathUtils.cos(a), MathUtils.sin(a), 0);
    }

    /**
     * Tanks lock onto where you are, show a warning line for TANK_WINDUP
     * seconds, then fire a shell down that line. Roll out of the line.
     */
    private void updateTankGun(Enemy e, float dt, float dist, float dx, float dz) {
        if (e.windup > 0) {
            e.windup -= dt;
            if (e.windup <= 0) {
                e.windup = 0;
                e.cooldown = 4.5f;   // reload time between shells
                Bullet b = new Bullet();
                b.enemy = true;
                b.shell = true;
                b.x = e.x + e.aimX * 1.6f;
                b.z = e.z + e.aimZ * 1.6f;
                b.vx = e.aimX * SHELL_SPEED;
                b.vz = e.aimZ * SHELL_SPEED;
                b.life = TANK_RANGE / SHELL_SPEED;
                b.damage = 20;
                bullets.add(b);
                emit(Ev.TANK_FIRE, b.x, b.z, e.aimX, e.aimZ, 0);
            }
            return;
        }
        e.cooldown -= dt;
        if (dist < TANK_RANGE - 1f && e.cooldown <= 0) {
            e.windup = TANK_WINDUP;
            e.aimX = dx / dist;
            e.aimZ = dz / dist;
            emit(Ev.TANK_AIM, e.x, e.z, e.aimX, e.aimZ, 0);
        }
    }

    private void updateBullets(float dt) {
        Soldier p = player;
        for (int i = bullets.size - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            b.x += b.vx * dt;
            b.z += b.vz * dt;
            b.life -= dt;

            boolean rock = hitsRock(b.x, b.z);
            boolean dead = b.life <= 0 || Math.abs(b.x) > halfWidth(b.z) + 0.5f || rock;

            if (b.shell) {
                // Shells go off at the end of their run, on a rock, or on you.
                float dx = b.x - p.x, dz = b.z - p.z;
                if (dead || (!p.dodging() && dx * dx + dz * dz < 0.7f * 0.7f)) {
                    blast(b.x, b.z, b.damage);
                    bullets.removeIndex(i);
                }
                continue;
            }

            if (rock && !b.enemy) emit(Ev.IMPACT, b.x, b.z, -b.vx, -b.vz, 0);

            if (!dead && b.enemy) {
                float dx = b.x - p.x, dz = b.z - p.z;
                // Rolling through bullets is the whole point of the roll.
                if (!p.dodging() && dx * dx + dz * dz < 0.55f * 0.55f) {
                    hurtPlayer(b.damage, b.vx, b.vz);
                    dead = true;
                }
            } else if (!dead) {
                for (int j = enemies.size - 1; j >= 0; j--) {
                    Enemy e = enemies.get(j);
                    if (e == b.lastHit) continue;
                    float dx = b.x - e.x, dz = b.z - e.z;
                    float r = e.radius + 0.15f;
                    if (dx * dx + dz * dz < r * r) {
                        float sp = (float) Math.sqrt(b.vx * b.vx + b.vz * b.vz);
                        float nx = b.vx / sp, nz = b.vz / sp;
                        float dmg = b.damage * (e.tank && p.bazooka ? 3f : 1f);
                        damageEnemy(j, dmg, nx, nz);
                        if (b.pierce > 0) { b.pierce--; b.lastHit = e; }
                        else dead = true;
                        break;
                    }
                }
            }
            if (dead) bullets.removeIndex(i);
        }
    }

    private void hurtPlayer(float dmg, float fromX, float fromZ) {
        Soldier p = player;
        p.hp -= dmg;
        p.hurtTimer = 0.25f;
        emit(Ev.PLAYER_HIT, p.x, p.z, fromX, fromZ, dmg);
    }

    /** nx,nz = direction the hit came from, used for knockback and which way they fall. */
    private void damageEnemy(int index, float dmg, float nx, float nz) {
        Enemy e = enemies.get(index);
        e.hp -= dmg;
        e.hitTimer = 0.1f;
        if (!e.tank) {
            e.x += nx * 0.3f;
            e.z += nz * 0.3f;
            e.stagger = 0.15f;
        }
        if (e.hp <= 0) {
            enemies.removeIndex(index);
            kills++;
            if (e.tank) emit(Ev.TANK_KILL, e.x, e.z, e.faceX, e.faceZ, 0);
            else emit(Ev.KILL, e.x, e.z, nx, nz, 0);
        } else {
            emit(e.tank ? Ev.ARMOR_HIT : Ev.HIT, e.x, e.z, nx, nz, dmg);
        }
    }

    /** Shell explosion. Hurts you unless you're rolling, and hurts their own infantry too. */
    private void blast(float x, float z, float dmg) {
        emit(Ev.BLAST, x, z, 0, 0, 0);
        Soldier p = player;
        float dx = p.x - x, dz = p.z - z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d < BLAST_RADIUS && !p.dodging()) hurtPlayer(dmg * (1f - 0.5f * d / BLAST_RADIUS), dx, dz);
        for (int j = enemies.size - 1; j >= 0; j--) {
            Enemy e = enemies.get(j);
            if (e.tank) continue;
            float ex = e.x - x, ez = e.z - z;
            float ed = (float) Math.sqrt(ex * ex + ez * ez);
            if (ed < BLAST_RADIUS) {
                float nx = ed > 0.01f ? ex / ed : 0, nz = ed > 0.01f ? ez / ed : 1;
                damageEnemy(j, 40, nx, nz);
            }
        }
    }

    private boolean hitsRock(float x, float z) {
        for (Rock r : rocks) {
            float dx = x - r.x, dz = z - r.z;
            if (dx * dx + dz * dz < r.r * r.r) return true;
        }
        return false;
    }

    private void pushOutOfRocks(Body b, float radius) {
        for (Rock r : rocks) {
            float dx = b.x - r.x, dz = b.z - r.z;
            float min = r.r + radius;
            float d2 = dx * dx + dz * dz;
            if (d2 < min * min && d2 > 0.0001f) {
                float d = (float) Math.sqrt(d2);
                b.x = r.x + dx / d * min;
                b.z = r.z + dz / d * min;
            }
        }
    }

    // ---- between days -------------------------------------------------------

    private void endDay() {
        if (day == DAYS.length - 1) { state = State.WON; return; }
        offer.clear();
        Array<Upgrade> pool = new Array<>(Upgrade.values());
        if (player.bazooka) pool.removeValue(Upgrade.BAZOOKA, true);
        if (line >= 100) pool.removeValue(Upgrade.AIR_COVER, true);
        pool.shuffle();
        for (int i = 0; i < 3 && i < pool.size; i++) offer.add(pool.get(i));
        state = State.UPGRADE;
    }

    public void choose(Upgrade u) {
        Soldier p = player;
        switch (u) {
            case THOMPSON: p.pellets++; break;
            case GARAND: p.damage *= 1.3f; break;
            case QUICK_HANDS: p.fireRate *= 1.25f; break;
            case FIELD_DRESSING: p.maxHp += 25; p.hp = p.maxHp; break;
            case JUMP_BOOTS: p.speed *= 1.15f; break;
            case BAZOOKA: p.bazooka = true; break;
            case AP_ROUNDS: p.pierce++; break;
            case AIR_COVER: line = Math.min(100, line + 30); break;
        }
        day++;
        startDay();
    }
}
