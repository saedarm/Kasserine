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

    // MG42 teams: dig in, then sweep a fixed arc with bursts
    public static final float MG_ARC = 0.95f;         // half-width of the field of fire, radians (about 55 degrees)
    public static final float MG_RANGE = 17f;
    public static final float MG_TURN = 0.75f;         // how fast the gun swings, radians per second
    public static final float MG_SETUP = 1.4f;        // seconds to dig in before the first burst
    public static final float MG_BURST = 1.8f;
    public static final float MG_RELOAD = 1.8f;
    public static final float MG_RATE = 12f;          // rounds per second in a burst

    // Grenadiers: hang back and lob grenades where you're standing
    public static final float GRENADE_WINDUP = 0.5f;  // arm back before the throw
    public static final float GRENADE_FUSE = 0.75f;   // after it lands, before it goes off
    public static final float GRENADE_RADIUS = 1.6f;

    // Stuka strikes: a line of bombs down the canyon
    public static final float STUKA_PASS = 3f;        // seconds for the plane to cross the screen
    public static final float BOMB_RADIUS = 2.1f;

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
        MG_SHOT,       // an MG42 fired one round
        MG_SETUP,      // an MG42 team started digging in
        TANK_AIM,      // a tank started its windup
        TANK_FIRE,     // a tank fired its shell
        GRENADE_THROW, // a grenadier threw. x,z = from, dx,dz = where it will land
        GRENADE_LAND,  // a grenade hit the ground and is about to go off
        STUKA,         // a bombing run started. x,z = center, dx,dz = flight direction
        HIT,           // your bullet hit infantry. dx,dz = bullet direction
        ARMOR_HIT,     // your bullet hit a tank
        KILL,          // infantry died. dx,dz = which way they fall
        TANK_KILL,     // a tank died. dx,dz = its facing
        IMPACT,        // a bullet hit a rock
        BLAST,         // something exploded. amount = radius
        PLAYER_HIT,    // you took damage. amount = damage
        DODGE,         // you started a roll. dx,dz = roll direction
        BREAKTHROUGH,  // someone got past you and hurt the line. amount = line lost
        FIRST_SIGHTING // a new kind of threat showed up for the first time this run. amount = Threat ordinal
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

    /** Every kind of thing that can kill you. Used for spawning and first-time tips. */
    public enum Threat { RIFLE, GRENADIER, MG, TANK, STUKA }

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
        public Threat kind;
        public float hp, speed, radius, cooldown, hitTimer, stagger;
        public float faceX = 0, faceZ = -1;
        /** Tanks: counts down while about to fire along aimX/aimZ. Grenadiers: arm back. MG: digging in. */
        public float windup, aimX, aimZ;
        /** Grenadiers: where the grenade will land. */
        public float targetX, targetZ;
        /** MG42: where it digs in, whether it has, the middle of its arc, where the gun points, burst left. */
        public float setupZ, arcCenter, gun, burst;
        public boolean deployed;

        public boolean isTank() { return kind == Threat.TANK; }
    }

    public static class Bullet {
        public float x, z, vx, vz, life, damage;
        public boolean enemy, shell;
        public int pierce;
        public Enemy lastHit;
    }

    /** A grenade in the air. t runs from 0 to flight. */
    public static class Grenade {
        public float x0, z0, x1, z1, t, flight;
        public float x() { return MathUtils.lerp(x0, x1, t / flight); }
        public float z() { return MathUtils.lerp(z0, z1, t / flight); }
        /** Height above the ground, a simple arc. */
        public float y() { float k = t / flight; return 1.1f + 3.2f * k * (1 - k) - 0.9f * k; }
    }

    /** Something on the ground about to explode: a landed grenade or a falling bomb. */
    public static class Hazard {
        public float x, z, fuse, maxFuse, radius, damage;
        public boolean bomb;
    }

    /** A Stuka crossing the canyon. Only for drawing: the bombs are Hazards. */
    public static class Strike {
        public float cx, cz, dx, dz, t;
        /** Where the plane is right now, along its path. */
        public float planeOffset() { return -30f + 60f * t / STUKA_PASS; }
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
    public final Array<Grenade> grenades = new Array<>();
    public final Array<Hazard> hazards = new Array<>();
    public final Array<Strike> strikes = new Array<>();
    public final Array<Rock> rocks = new Array<>();
    public final Array<Upgrade> offer = new Array<>();

    public State state;
    public int day;          // 0-based
    public float clock;      // seconds left in the day
    public float line;       // 0..100, enemies that slip past knock it down
    public int kills;
    /** Goes up by one every time a day starts, so the renderer knows to clear old effects. */
    public int dayCounter;

    private float spawnTimer, stukaTimer;
    private final boolean[] seen = new boolean[Threat.values().length];

    public Battle() {
        buildRocks();
        reset();
    }

    public void reset() {
        Soldier p = player;
        p.x = 0; p.z = 6; p.maxHp = 100; p.hp = 100; p.speed = 5f;
        p.fireRate = 5f; p.damage = 10f; p.pellets = 1; p.pierce = 0; p.bazooka = false;
        day = 0; line = 100; kills = 0;
        java.util.Arrays.fill(seen, false);
        startDay();
    }

    private void startDay() {
        enemies.clear();
        bullets.clear();
        grenades.clear();
        hazards.clear();
        strikes.clear();
        clock = DAY_LENGTH;
        spawnTimer = 1.5f;
        stukaTimer = MathUtils.random(9f, 14f);
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

    private void sighted(Threat t, float x, float z) {
        if (seen[t.ordinal()]) return;
        seen[t.ordinal()] = true;
        emit(Ev.FIRST_SIGHTING, x, z, 0, 0, t.ordinal());
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
        updateStukas(dt);
        updateEnemies(dt);
        updateBullets(dt);
        updateGrenades(dt);
        updateHazards(dt);

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

    // ---- spawning -----------------------------------------------------------

    /**
     * Which threats show up on which day. Each day adds one new thing:
     * day 1 riflemen and a few grenadiers, day 2 MG42 teams,
     * day 3 tanks and Stukas, days 4 and 5 more of everything.
     */
    private Threat rollThreat() {
        int mgs = 0;
        for (Enemy e : enemies) if (e.kind == Threat.MG) mgs++;
        float r = MathUtils.random();
        float tank = day >= 2 ? 0.05f + day * 0.025f : 0;
        float mg = day >= 1 && mgs < (day >= 3 ? 2 : 1) ? 0.06f + day * 0.01f : 0;
        float gren = 0.10f + day * 0.035f;
        if (r < tank) return Threat.TANK;
        if (r < tank + mg) return Threat.MG;
        if (r < tank + mg + gren) return Threat.GRENADIER;
        return Threat.RIFLE;
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
            e.kind = rollThreat();
            // Just off the top of the screen, so the fight comes to you.
            e.z = Math.min(SPAWN_Z, player.z + 24f) + MathUtils.random(-1.5f, 1.5f);
            e.x = MathUtils.random(-1f, 1f) * (halfWidth(e.z) - 1.5f);
            e.radius = 0.4f;
            e.cooldown = MathUtils.random(1.5f, 3f);
            switch (e.kind) {
                case TANK: e.hp = 140; e.speed = 1.3f; e.radius = 1.2f; break;
                case MG:
                    e.hp = 50; e.speed = 2.6f; e.radius = 0.6f;
                    // Comes in just above the top of the screen and digs in a few steps later.
                    e.z = Math.min(SPAWN_Z, player.z + 18f);
                    e.setupZ = Math.min(e.z - 3f, player.z + MathUtils.random(10f, 13f));
                    e.windup = -4f; // negative = walking; digs in when this reaches 0 even if blocked
                    break;
                case GRENADIER: e.hp = 20 + day * 3; e.speed = 2.2f; e.cooldown = MathUtils.random(1.0f, 2.0f); break;
                default: e.hp = 20 + day * 4; e.speed = MathUtils.random(2.0f, 2.8f);
            }
            enemies.add(e);
            sighted(e.kind, e.x, e.z);
        }
    }

    /** From day 3, a Stuka every 13-19 seconds lays a line of bombs through where you're standing. */
    private void updateStukas(float dt) {
        for (int i = strikes.size - 1; i >= 0; i--) {
            Strike s = strikes.get(i);
            s.t += dt;
            if (s.t > STUKA_PASS + 0.5f) strikes.removeIndex(i);
        }
        if (day < 2) return;
        stukaTimer -= dt;
        if (stukaTimer > 0) return;
        stukaTimer = MathUtils.random(13f, 19f) - day;

        Soldier p = player;
        // Dives down the canyon toward you, give or take 30 degrees.
        float a = MathUtils.random(-0.5f, 0.5f);
        Strike s = new Strike();
        s.cx = p.x; s.cz = p.z;
        s.dx = MathUtils.sin(a); s.dz = -MathUtils.cos(a);
        strikes.add(s);
        emit(Ev.STUKA, s.cx, s.cz, s.dx, s.dz, 0);
        sighted(Threat.STUKA, s.cx, s.cz);

        // Five bombs, 3 units apart along the path, each going off half a second
        // after the plane passes over it. The markers show from the start.
        for (int b = -2; b <= 2; b++) {
            float off = b * 3f;
            Hazard h = new Hazard();
            h.x = s.cx + s.dx * off;
            h.z = s.cz + s.dz * off;
            h.maxFuse = h.fuse = (off + 30f) / 60f * STUKA_PASS + 0.5f;
            h.radius = BOMB_RADIUS;
            h.damage = 22;
            h.bomb = true;
            hazards.add(h);
        }
    }

    // ---- enemies ------------------------------------------------------------

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

            float moveScale = 1f;
            switch (e.kind) {
                case RIFLE: if (dist < 6f) moveScale = 0f; break;        // stop and shoot at mid range
                case GRENADIER:                                          // keep 9-11 units away
                    if (dist < 8f) { tx = -tx; tz = -tz; moveScale = 0.6f; }
                    else if (dist < 11f) moveScale = 0f;
                    break;
                case MG:                                                 // walk to the spot, then stay
                    if (e.deployed) moveScale = 0f;
                    else { tx = -e.x * 0.05f; tz = -1f; }
                    break;
                default:
            }
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
            if (e.deployed) { sx = 0; sz = 0; } // a dug-in gun doesn't get shoved around

            e.x += (tx * e.speed * moveScale) * dt + sx * 4f * dt;
            e.z += (tz * e.speed * moveScale) * dt + sz * 4f * dt;
            float hw = halfWidth(e.z) - e.radius;
            e.x = MathUtils.clamp(e.x, -hw, hw);
            pushOutOfRocks(e, e.radius);

            // Which way they face: tanks lock on while aiming, MGs face their gun, everyone else faces you.
            if (e.kind == Threat.MG && e.deployed) { e.faceX = MathUtils.cos(e.gun); e.faceZ = MathUtils.sin(e.gun); }
            else if (e.kind == Threat.TANK && e.windup > 0) { e.faceX = e.aimX; e.faceZ = e.aimZ; }
            else { e.faceX = dx / dist; e.faceZ = dz / dist; }

            // Slipped past you out the south end.
            if (e.z < 0.5f) {
                float lost = e.isTank() ? 25 : 8;
                line -= lost;
                emit(Ev.BREAKTHROUGH, e.x, e.z, 0, -1, lost);
                enemies.removeIndex(i);
                continue;
            }

            switch (e.kind) {
                case TANK: updateTankGun(e, dt, dist, dx, dz); break;
                case MG: updateMg(e, dt, dist, dx, dz); break;
                case GRENADIER: updateGrenadier(e, dt, dist); break;
                default: updateRifle(e, dt, dist, dx, dz);
            }
        }
    }

    private void updateRifle(Enemy e, float dt, float dist, float dx, float dz) {
        e.cooldown -= dt;
        if (dist > 11f || e.cooldown > 0 || e.stagger > 0) return;
        e.cooldown = MathUtils.random(2.4f, 3.6f);
        float a = MathUtils.atan2(dz, dx) + MathUtils.random(-0.08f, 0.08f);
        enemyBullet(e.x, e.z, a, 9f, 11f, 5);
        emit(Ev.ENEMY_SHOT, e.x + MathUtils.cos(a) * 0.6f, e.z + MathUtils.sin(a) * 0.6f, MathUtils.cos(a), MathUtils.sin(a), 0);
    }

    private void enemyBullet(float x, float z, float angle, float speed, float range, float damage) {
        Bullet b = new Bullet();
        b.enemy = true;
        b.x = x; b.z = z;
        b.vx = MathUtils.cos(angle) * speed;
        b.vz = MathUtils.sin(angle) * speed;
        b.life = range / speed + 0.3f;
        b.damage = damage;
        bullets.add(b);
    }

    /**
     * MG42: walks to its spot and digs in facing you. From then on the gun can
     * only swing MG_ARC either side of that direction, and it swings slowly.
     * Get outside the arc and it can't touch you. Rocks stop its bullets.
     */
    private void updateMg(Enemy e, float dt, float dist, float dx, float dz) {
        if (!e.deployed) {
            e.windup += dt;
            if (e.z <= e.setupZ || dist < 11f || e.windup >= 0) {
                e.deployed = true;
                e.windup = MG_SETUP;
                e.arcCenter = e.gun = MathUtils.atan2(dz, dx);
                e.cooldown = 0;
                emit(Ev.MG_SETUP, e.x, e.z, dx / dist, dz / dist, 0);
            }
            return;
        }
        if (e.windup > 0) { e.windup -= dt; return; }

        // Turn toward you, but never outside the arc.
        float want = MathUtils.atan2(dz, dx);
        float rel = angleDiff(want, e.arcCenter);
        boolean inArc = Math.abs(rel) <= MG_ARC && dist < MG_RANGE;
        float target = e.arcCenter + MathUtils.clamp(rel, -MG_ARC, MG_ARC);
        float turn = MathUtils.clamp(angleDiff(target, e.gun), -MG_TURN * dt, MG_TURN * dt);
        e.gun += turn;

        if (e.burst > 0) {
            e.burst -= dt;
            e.cooldown -= dt;
            if (e.cooldown <= 0) {
                e.cooldown += 1f / MG_RATE;
                float a = e.gun + MathUtils.random(-0.07f, 0.07f);
                enemyBullet(e.x + MathUtils.cos(a) * 0.9f, e.z + MathUtils.sin(a) * 0.9f, a, 15f, MG_RANGE, 2);
                emit(Ev.MG_SHOT, e.x + MathUtils.cos(a) * 1.1f, e.z + MathUtils.sin(a) * 1.1f, MathUtils.cos(a), MathUtils.sin(a), 0);
            }
            if (e.burst <= 0) e.cooldown = MG_RELOAD;
        } else {
            e.cooldown -= dt;
            if (e.cooldown <= 0 && inArc) { e.burst = MG_BURST; e.cooldown = 0; }
        }
    }

    /** Grenadier: arms back for half a second, then lobs a grenade at where you were standing. */
    private void updateGrenadier(Enemy e, float dt, float dist) {
        Soldier p = player;
        if (e.windup > 0) {
            e.windup -= dt;
            if (e.windup <= 0) {
                Grenade g = new Grenade();
                g.x0 = e.x; g.z0 = e.z;
                g.x1 = e.targetX; g.z1 = e.targetZ;
                float d = (float) Math.sqrt((g.x1 - g.x0) * (g.x1 - g.x0) + (g.z1 - g.z0) * (g.z1 - g.z0));
                g.flight = MathUtils.clamp(d / 11f, 0.55f, 1.2f);
                grenades.add(g);
                e.cooldown = MathUtils.random(3.2f, 4.4f);
                emit(Ev.GRENADE_THROW, e.x, e.z, g.x1, g.z1, 0);
            }
            return;
        }
        e.cooldown -= dt;
        if (e.cooldown <= 0 && dist < 13.5f && e.stagger <= 0) {
            e.windup = GRENADE_WINDUP;
            e.targetX = p.x + MathUtils.random(-0.6f, 0.6f);
            e.targetZ = p.z + MathUtils.random(-0.6f, 0.6f);
        }
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

    // ---- projectiles and explosions -----------------------------------------

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
                    blast(b.x, b.z, b.damage, BLAST_RADIUS);
                    bullets.removeIndex(i);
                }
                continue;
            }

            if (rock) emit(Ev.IMPACT, b.x, b.z, -b.vx, -b.vz, 0);

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
                        float dmg = b.damage * (e.isTank() && p.bazooka ? 3f : 1f);
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

    private void updateGrenades(float dt) {
        for (int i = grenades.size - 1; i >= 0; i--) {
            Grenade g = grenades.get(i);
            g.t += dt;
            if (g.t >= g.flight) {
                Hazard h = new Hazard();
                h.x = g.x1; h.z = g.z1;
                h.maxFuse = h.fuse = GRENADE_FUSE;
                h.radius = GRENADE_RADIUS;
                h.damage = 15;
                hazards.add(h);
                emit(Ev.GRENADE_LAND, h.x, h.z, 0, 0, 0);
                grenades.removeIndex(i);
            }
        }
    }

    private void updateHazards(float dt) {
        for (int i = hazards.size - 1; i >= 0; i--) {
            Hazard h = hazards.get(i);
            h.fuse -= dt;
            if (h.fuse <= 0) {
                hazards.removeIndex(i);
                blast(h.x, h.z, h.damage, h.radius);
            }
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
        if (!e.isTank() && !e.deployed) {
            e.x += nx * 0.3f;
            e.z += nz * 0.3f;
            e.stagger = 0.15f;
        }
        if (e.hp <= 0) {
            enemies.removeIndex(index);
            kills++;
            if (e.isTank()) emit(Ev.TANK_KILL, e.x, e.z, e.faceX, e.faceZ, 0);
            else emit(Ev.KILL, e.x, e.z, nx, nz, 0);
        } else {
            emit(e.isTank() ? Ev.ARMOR_HIT : Ev.HIT, e.x, e.z, nx, nz, dmg);
        }
    }

    /**
     * Explosion: shells, grenades and bombs. Hurts you unless you're rolling,
     * and hurts their own infantry too.
     */
    private void blast(float x, float z, float dmg, float radius) {
        emit(Ev.BLAST, x, z, 0, 0, radius);
        Soldier p = player;
        float dx = p.x - x, dz = p.z - z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d < radius && !p.dodging()) hurtPlayer(dmg * (1f - 0.5f * d / radius), dx, dz);
        for (int j = enemies.size - 1; j >= 0; j--) {
            Enemy e = enemies.get(j);
            if (e.isTank()) continue;
            float ex = e.x - x, ez = e.z - z;
            float ed = (float) Math.sqrt(ex * ex + ez * ez);
            if (ed < radius) {
                float nx = ed > 0.01f ? ex / ed : 0, nz = ed > 0.01f ? ez / ed : 1;
                damageEnemy(j, 40, nx, nz);
            }
        }
    }

    private static float angleDiff(float a, float b) {
        float d = a - b;
        while (d > MathUtils.PI) d -= MathUtils.PI2;
        while (d < -MathUtils.PI) d += MathUtils.PI2;
        return d;
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
