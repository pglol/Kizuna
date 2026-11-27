package net.pglol.katamod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench model (.bbmodel, Bedrock/GeckoLib format) loaded straight from the team's files, with
 * its animations: cubes in bones, posed by bone rotations, animated by linear keyframes. Drawn with any
 * vertex consumer; the screen-space bounds of named bones are collected as it draws (for hovering).
 */
final class BbModel {
    static final class Cube {
        float[] from, to, origin, rot;
        float inflate;
        float[][] uv = new float[6][]; // north, east, south, west, up, down
    }

    static final class Bone {
        String name;
        float[] origin, rot;
        final List<Bone> kids = new ArrayList<>();
        final List<Cube> cubes = new ArrayList<>();
    }

    /** One channel of one bone in an animation: keyframe times and their x, y, z. */
    static final class Track {
        float[] times = new float[0];
        float[][] values = new float[0][];

        float[] at(float t) {
            if (times.length == 0) return null;
            if (t <= times[0]) return values[0];
            for (int i = 1; i < times.length; i++) {
                if (t <= times[i]) {
                    float k = (t - times[i - 1]) / Math.max(1e-5f, times[i] - times[i - 1]);
                    float[] a = values[i - 1], b = values[i];
                    return new float[] {a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k};
                }
            }
            return values[values.length - 1];
        }
    }

    static final class Anim {
        String name, loop;
        float length;
        final Map<String, Track[]> bones = new HashMap<>(); // rotation, position, scale

        float local(float t) {
            if ("loop".equals(loop) && length > 0) return t % length;
            return Math.min(t, length);
        }

        boolean done(float t) {
            return !"loop".equals(loop) && t >= length;
        }
    }

    /** A bone's animated pose this frame: extra rotation, offset, scale. */
    static final class BonePose {
        float[] rot, pos, scale;
    }

    final List<Bone> roots = new ArrayList<>();
    final Map<String, Anim> anims = new HashMap<>();
    float texW = 64, texH = 64;

    static BbModel load(Identifier id) {
        BbModel m = new BbModel();
        try (Reader r = MinecraftClient.getInstance().getResourceManager().getResource(id).orElseThrow().getReader()) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (o.has("resolution")) {
                m.texW = o.getAsJsonObject("resolution").get("width").getAsFloat();
                m.texH = o.getAsJsonObject("resolution").get("height").getAsFloat();
            }
            Map<String, Cube> cubes = new HashMap<>();
            String[] faces = {"north", "east", "south", "west", "up", "down"};
            for (JsonElement e : o.getAsJsonArray("elements")) {
                JsonObject c = e.getAsJsonObject();
                if (c.has("type") && !"cube".equals(c.get("type").getAsString())) continue;
                Cube cube = new Cube();
                cube.from = floats(c.getAsJsonArray("from"));
                cube.to = floats(c.getAsJsonArray("to"));
                cube.origin = c.has("origin") ? floats(c.getAsJsonArray("origin")) : new float[3];
                cube.rot = c.has("rotation") ? floats(c.getAsJsonArray("rotation")) : null;
                cube.inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0;
                JsonObject f = c.getAsJsonObject("faces");
                for (int i = 0; i < 6; i++) {
                    if (f == null || !f.has(faces[i])) continue;
                    JsonObject face = f.getAsJsonObject(faces[i]);
                    if (face.has("texture") && face.get("texture").isJsonNull()) continue;
                    if (face.has("uv")) cube.uv[i] = floats(face.getAsJsonArray("uv"));
                }
                cubes.put(c.get("uuid").getAsString(), cube);
            }
            for (JsonElement e : o.getAsJsonArray("outliner")) {
                if (e.isJsonObject()) m.roots.add(bone(e.getAsJsonObject(), cubes));
            }
            if (o.has("animations")) {
                for (JsonElement e : o.getAsJsonArray("animations")) {
                    JsonObject a = e.getAsJsonObject();
                    Anim an = new Anim();
                    an.name = a.get("name").getAsString();
                    an.loop = a.has("loop") ? a.get("loop").getAsString() : "once";
                    an.length = a.has("length") ? a.get("length").getAsFloat() : 0;
                    if (a.has("animators")) {
                        for (Map.Entry<String, JsonElement> en : a.getAsJsonObject("animators").entrySet()) {
                            JsonObject ar = en.getValue().getAsJsonObject();
                            if (!ar.has("name") || !ar.has("keyframes")) continue;
                            Map<String, List<float[]>> chans = new HashMap<>();
                            for (JsonElement ke : ar.getAsJsonArray("keyframes")) {
                                JsonObject k = ke.getAsJsonObject();
                                JsonObject dp = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
                                chans.computeIfAbsent(k.get("channel").getAsString(), x -> new ArrayList<>())
                                    .add(new float[] {k.get("time").getAsFloat(), num(dp, "x"), num(dp, "y"), num(dp, "z")});
                            }
                            Track[] tr = new Track[3];
                            String[] names = {"rotation", "position", "scale"};
                            for (int i = 0; i < 3; i++) {
                                List<float[]> l = chans.get(names[i]);
                                if (l == null) continue;
                                l.sort((p, q) -> Float.compare(p[0], q[0]));
                                Track t = new Track();
                                t.times = new float[l.size()];
                                t.values = new float[l.size()][];
                                for (int j = 0; j < l.size(); j++) {
                                    t.times[j] = l.get(j)[0];
                                    t.values[j] = new float[] {l.get(j)[1], l.get(j)[2], l.get(j)[3]};
                                }
                                tr[i] = t;
                            }
                            an.bones.put(ar.get("name").getAsString(), tr);
                        }
                    }
                    m.anims.put(an.name, an);
                }
            }
        } catch (Exception ex) {
            net.pglol.kata.KataCore.LOG.error("Could not load model {}", id, ex);
        }
        return m;
    }

    private static Bone bone(JsonObject g, Map<String, Cube> cubes) {
        Bone b = new Bone();
        b.name = g.get("name").getAsString();
        b.origin = g.has("origin") ? floats(g.getAsJsonArray("origin")) : new float[3];
        b.rot = g.has("rotation") ? floats(g.getAsJsonArray("rotation")) : new float[3];
        for (JsonElement c : g.getAsJsonArray("children")) {
            if (c.isJsonPrimitive()) {
                Cube cube = cubes.get(c.getAsString());
                if (cube != null) b.cubes.add(cube);
            } else if (c.isJsonObject()) b.kids.add(bone(c.getAsJsonObject(), cubes));
        }
        return b;
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
        return f;
    }

    private static float num(JsonObject o, String k) {
        if (!o.has(k)) return 0;
        try {
            return Float.parseFloat(o.get(k).getAsString().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Sample an animation at time t into the pose map (bone name to its animated pose). */
    void sample(String anim, float t, Map<String, BonePose> pose) {
        Anim a = anims.get(anim);
        if (a == null) return;
        float lt = a.local(t);
        for (Map.Entry<String, Track[]> en : a.bones.entrySet()) {
            BonePose bp = pose.computeIfAbsent(en.getKey(), k -> new BonePose());
            Track[] tr = en.getValue();
            if (tr[0] != null) bp.rot = tr[0].at(lt);
            if (tr[1] != null) bp.pos = tr[1].at(lt);
            if (tr[2] != null) bp.scale = tr[2].at(lt);
        }
    }

    /**
     * Draw it. Units are Blockbench pixels (16 a block). Bounds of the named bones (and everything under
     * them) are written into bounds as screen rectangles {minX, minY, maxX, maxY}.
     */
    void render(MatrixStack ms, VertexConsumer vc, Map<String, BonePose> pose, int argb, Map<String, float[]> bounds) {
        render(ms, vc, pose, argb, bounds, null);
    }

    /** As render, also collecting each named bone's faces on screen (for exact hovering: see hit). */
    void render(MatrixStack ms, VertexConsumer vc, Map<String, BonePose> pose, int argb, Map<String, float[]> bounds, Map<String, List<float[]>> faces) {
        this.faces = faces;
        for (Bone b : roots) bone(ms, vc, b, pose, argb, bounds, null);
        this.faces = null;
        cur = null;
    }

    private Map<String, List<float[]>> faces;
    private List<float[]> cur;

    /**
     * Which named bone's faces are under (x, y), the frontmost if several overlap (nearest the viewer:
     * the largest screen z), or null.
     */
    static String hit(Map<String, List<float[]>> faces, double x, double y) {
        String best = null;
        float bestZ = -Float.MAX_VALUE;
        for (Map.Entry<String, List<float[]>> en : faces.entrySet())
            for (float[] q : en.getValue()) {
                if (!inside(q, x, y)) continue;
                float z = (q[2] + q[5] + q[8] + q[11]) / 4;
                if (z > bestZ) {
                    bestZ = z;
                    best = en.getKey();
                }
            }
        return best;
    }

    private static boolean inside(float[] q, double x, double y) {
        // A convex quad on screen: the point is on the same side of all four edges.
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            float ax = q[i * 3], ay = q[i * 3 + 1], bx = q[((i + 1) % 4) * 3], by = q[((i + 1) % 4) * 3 + 1];
            double cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            int s = cr > 0 ? 1 : cr < 0 ? -1 : 0;
            if (s == 0) continue;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return sign != 0;
    }

    private void bone(MatrixStack ms, VertexConsumer vc, Bone b, Map<String, BonePose> pose, int argb, Map<String, float[]> bounds, float[] box) {
        BonePose bp = pose == null ? null : pose.get(b.name);
        if (bp != null && bp.scale != null && bp.scale[0] == 0 && bp.scale[1] == 0 && bp.scale[2] == 0) return;
        ms.push();
        if (bp != null && bp.pos != null) ms.translate(-bp.pos[0], bp.pos[1], bp.pos[2]);
        ms.translate(b.origin[0], b.origin[1], b.origin[2]);
        // Bedrock animation rotations come with X and Y flipped relative to a bone's own rotation.
        float rx = b.rot[0], ry = b.rot[1], rz = b.rot[2];
        if (bp != null && bp.rot != null) {
            rx -= bp.rot[0];
            ry -= bp.rot[1];
            rz += bp.rot[2];
        }
        if (rz != 0) ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rz));
        if (ry != 0) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ry));
        if (rx != 0) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rx));
        if (bp != null && bp.scale != null) ms.scale(bp.scale[0], bp.scale[1], bp.scale[2]);
        ms.translate(-b.origin[0], -b.origin[1], -b.origin[2]);
        float[] mine = box;
        List<float[]> was = cur;
        if (bounds != null && bounds.containsKey(b.name)) {
            mine = bounds.get(b.name);
            mine[0] = mine[1] = Float.MAX_VALUE;
            mine[2] = mine[3] = -Float.MAX_VALUE;
            if (faces != null) {
                cur = faces.computeIfAbsent(b.name, k -> new ArrayList<>());
                cur.clear();
            }
        }
        // Glow shells and sparkles don't count for hovering (only the thing itself).
        boolean shell = b.name.endsWith("_glow") || b.name.endsWith("_sparkles");
        List<float[]> keep = cur;
        if (shell) cur = null;
        for (Cube c : b.cubes) cube(ms, vc, c, argb, mine);
        for (Bone k : b.kids) bone(ms, vc, k, pose, argb, bounds, mine);
        cur = shell ? keep : cur;
        if (bounds != null && bounds.containsKey(b.name)) cur = was;
        ms.pop();
    }

    private void cube(MatrixStack ms, VertexConsumer vc, Cube c, int argb, float[] box) {
        ms.push();
        if (c.rot != null) {
            ms.translate(c.origin[0], c.origin[1], c.origin[2]);
            if (c.rot[2] != 0) ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(c.rot[2]));
            if (c.rot[1] != 0) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(c.rot[1]));
            if (c.rot[0] != 0) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(c.rot[0]));
            ms.translate(-c.origin[0], -c.origin[1], -c.origin[2]);
        }
        float i = c.inflate;
        float x0 = Math.min(c.from[0], c.to[0]) - i, y0 = Math.min(c.from[1], c.to[1]) - i, z0 = Math.min(c.from[2], c.to[2]) - i;
        float x1 = Math.max(c.from[0], c.to[0]) + i, y1 = Math.max(c.from[1], c.to[1]) + i, z1 = Math.max(c.from[2], c.to[2]) + i;
        MatrixStack.Entry e = ms.peek();
        Matrix4f m = e.getPositionMatrix();
        // Per-face shading (a fixed light from above and in front), so the steel reads as solid.
        float[][][] q = {
            {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}},   // north (front)
            {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}},   // east
            {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}},   // south
            {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}},   // west
            {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},   // up
            {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}},   // down
        };
        float[] shade = {1f, 0.78f, 0.62f, 0.8f, 0.92f, 0.5f};
        float[][] normal = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        for (int f = 0; f < 6; f++) {
            float[] uv = c.uv[f];
            if (uv == null || (uv[0] == uv[2] && uv[1] == uv[3])) continue;
            float u0 = uv[0] / texW, v0 = uv[1] / texH, u1 = uv[2] / texW, v1 = uv[3] / texH;
            float[][] uvs = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            int a = argb >>> 24;
            int r = (int) (((argb >> 16) & 255) * shade[f]), g = (int) (((argb >> 8) & 255) * shade[f]), bl = (int) ((argb & 255) * shade[f]);
            float[] sq = cur != null ? new float[12] : null;
            for (int k = 0; k < 4; k++) {
                float[] p = q[f][k];
                if (sq != null) {
                    Vector3f s = m.transformPosition(new Vector3f(p[0], p[1], p[2]));
                    sq[k * 3] = s.x;
                    sq[k * 3 + 1] = s.y;
                    sq[k * 3 + 2] = s.z;
                }
                vc.vertex(m, p[0], p[1], p[2]).color(r, g, bl, a).texture(uvs[k][0], uvs[k][1]).overlay(OverlayTexture.DEFAULT_UV)
                    .light(0xF000F0).normal(e, normal[f][0], normal[f][1], normal[f][2]);
                if (box != null) {
                    Vector3f s = m.transformPosition(new Vector3f(p[0], p[1], p[2]));
                    box[0] = Math.min(box[0], s.x);
                    box[1] = Math.min(box[1], s.y);
                    box[2] = Math.max(box[2], s.x);
                    box[3] = Math.max(box[3], s.y);
                }
            }
            if (sq != null) cur.add(sq);
        }
        ms.pop();
    }
}
