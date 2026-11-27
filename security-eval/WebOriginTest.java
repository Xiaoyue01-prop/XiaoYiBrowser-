package com.example.webedge;

public final class WebOriginTest {
    public static void main(String[] args) {
        for (String origin : new String[]{"https://example.test", "http://localhost:8080/",
                "https://[::1]:443", "https://xn--bcher-kva.example", "https://example.test:65535"}) {
            if (!WebOrigin.accepts(origin)) throw new AssertionError("Rejected " + origin);
        }
        for (String origin : new String[]{null, "null", "", "file:///tmp", "content://provider",
                "https://user@example.test", "https://example.test:", "https://example.test:0",
                "https://example.test:65536", "https://example.test:bad", "https://example.test?q",
                "https://example.test/#fragment", "https://example.test/path", "https:example.test"}) {
            if (WebOrigin.accepts(origin)) throw new AssertionError("Accepted " + origin);
        }
        System.out.println("PASS WebOrigin (19 cases)");
    }
}
