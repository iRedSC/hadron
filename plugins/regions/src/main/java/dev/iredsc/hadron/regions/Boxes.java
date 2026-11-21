package dev.iredsc.hadron.regions;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Block boxes as sets of blocks: plain functions, so they're checked on the JVM without a server. */
final class Boxes {
    private Boxes() {}

    /** The blocks both hold. They must intersect. */
    static BoundingBox intersection(BoundingBox a, BoundingBox b) {
        if (!a.intersects(b)) throw new IllegalArgumentException(a + " and " + b + " share no block");
        return new BoundingBox(Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()),
                Math.max(a.minZ(), b.minZ()), Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()),
                Math.min(a.maxZ(), b.maxZ()));
    }

    /** Whether every block of {@code box} is in at least one of {@code cover}. */
    static boolean covered(BoundingBox box, List<BoundingBox> cover) {
        for (int i = 0; i < cover.size(); i++) {
            BoundingBox c = cover.get(i);
            if (!c.intersects(box)) continue;
            // What c leaves of box must be covered by the rest.
            List<BoundingBox> rest = cover.subList(i + 1, cover.size());
            for (BoundingBox piece : minus(box, c)) {
                if (!covered(piece, rest)) return false;
            }
            return true;
        }
        return false;
    }

    /** The blocks of {@code box} outside {@code cut}, as at most six boxes. They must intersect. */
    static List<BoundingBox> minus(BoundingBox box, BoundingBox cut) {
        List<BoundingBox> out = new ArrayList<>(6);
        int x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX(), y1 = box.maxY(), z1 = box.maxZ();
        if (x0 < cut.minX()) out.add(new BoundingBox(x0, y0, z0, cut.minX() - 1, y1, z1));
        if (x1 > cut.maxX()) out.add(new BoundingBox(cut.maxX() + 1, y0, z0, x1, y1, z1));
        x0 = Math.max(x0, cut.minX());
        x1 = Math.min(x1, cut.maxX());
        if (y0 < cut.minY()) out.add(new BoundingBox(x0, y0, z0, x1, cut.minY() - 1, z1));
        if (y1 > cut.maxY()) out.add(new BoundingBox(x0, cut.maxY() + 1, z0, x1, y1, z1));
        y0 = Math.max(y0, cut.minY());
        y1 = Math.min(y1, cut.maxY());
        if (z0 < cut.minZ()) out.add(new BoundingBox(x0, y0, z0, x1, y1, cut.minZ() - 1));
        if (z1 > cut.maxZ()) out.add(new BoundingBox(x0, y0, cut.maxZ() + 1, x1, y1, z1));
        return out;
    }
}
