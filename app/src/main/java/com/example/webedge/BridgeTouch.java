package com.example.webedge;

/** One native touch sequence for a single WebView and document. Times use uptimeMillis. */
final class BridgeTouch {
    private final long downAt;
    private final float x, y;
    private long releasedAt = -1;
    private boolean available = true;

    BridgeTouch(long downAt, float x, float y) {
        this.downAt = downAt;
        this.x = x;
        this.y = y;
    }

    void move(float currentX, float currentY, float slop) {
        float dx = currentX - x;
        float dy = currentY - y;
        if (dx * dx + dy * dy > slop * slop) available = false;
    }

    void release(long at) {
        releasedAt = at;
    }

    boolean consume(long now, long minimumHold, long maximumAge) {
        long age = now - downAt;
        long held = releasedAt < 0 ? age : releasedAt - downAt;
        if (!available || age < 0 || held < minimumHold || age > maximumAge) return false;
        available = false;
        return true;
    }
}
