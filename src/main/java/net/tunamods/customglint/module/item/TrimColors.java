package net.tunamods.customglint.module.item;

import java.util.ArrayList;
import java.util.List;

/**
 * Colour-list plumbing shared by {@link GlintTrimItem} and {@link GlowTrimItem}. Both store colours as a
 * {@code List<Integer>} data component (value-equal, so identical trims still stack) and work with
 * {@code int[]} everywhere else.
 */
final class TrimColors {
    private TrimColors() {}

    static int[] toIntArray(List<Integer> colors) {
        int[] out = new int[colors.size()];
        for (int i = 0; i < out.length; i++) out[i] = colors.get(i);
        return out;
    }

    /** Boxed copy for the component. The loop is deliberate: {@code List.of(int[])} would produce a
     *  one-element {@code List<int[]>}. */
    static List<Integer> toList(int[] colors) {
        List<Integer> out = new ArrayList<>(colors.length);
        for (int c : colors) out.add(c);
        return out;
    }

    /** All of {@code a}, then as much of {@code b} as fits under {@code cap}. */
    static int[] merge(int[] a, int[] b, int cap) {
        int total = Math.min(cap, a.length + b.length);
        int[] merged = new int[total];
        System.arraycopy(a, 0, merged, 0, Math.min(a.length, total));
        int fromB = total - a.length;
        if (fromB > 0) System.arraycopy(b, 0, merged, a.length, fromB);
        return merged;
    }
}
