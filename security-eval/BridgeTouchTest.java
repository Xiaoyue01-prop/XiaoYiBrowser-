package com.example.webedge;

public final class BridgeTouchTest {
    public static void main(String[] args) {
        BridgeTouch tap = new BridgeTouch(100, 0, 0);
        tap.release(130);
        check(!tap.consume(700, 500, 2500));
        check(tap.consume(700, 0, 2500));
        check(!tap.consume(701, 0, 2500));
        BridgeTouch hold = new BridgeTouch(100, 0, 0);
        check(!hold.consume(599, 500, 2500));
        check(hold.consume(600, 500, 2500));
        BridgeTouch drag = new BridgeTouch(100, 0, 0);
        drag.move(11, 0, 10);
        check(!drag.consume(700, 0, 2500));
        check(!new BridgeTouch(100, 1, 1).consume(2601, 0, 2500));
        System.out.println("PASS native gesture lifetime (7 checks)");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
