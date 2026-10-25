package com.gathin.tvhome;

/**
 * Which pictures the desk is willing to play behind a poster. Deliberately free of Android types so
 * it can be checked on its own: {@code javac Aspect.java /tmp/AspectCheck.java && java AspectCheck}.
 */
final class Aspect {

    private Aspect() {
    }

    /**
     * True for a landscape picture at least 16:9 wide. A portrait 1080x1920 has the same 1.78 ratio
     * as 1920x1080, so orientation is checked before the ratio. Wider cinema frames (1920x800 and
     * the like) pass: they letterbox on a 16:9 screen instead of filling it the wrong way. Unknown
     * sizes pass too, so an API that does not report a size cannot empty the desk.
     */
    static boolean wide(int width, int height, int rotate) {
        if (width <= 0 || height <= 0) return true;
        if (rotate % 180 != 0) {
            int swap = width;
            width = height;
            height = swap;
        }
        if (height > width) return false;
        return (double) width / height >= 1.70;
    }
}
