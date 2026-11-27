package com.example.webedge;

import java.net.URI;
import java.net.URISyntaxException;

/** Validates provider-supplied serialized origins before native dispatch or permission lookup. */
final class WebOrigin {
    private WebOrigin() {}

    static boolean accepts(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && !uri.getRawAuthority().endsWith(":")
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
                    && ("".equals(path) || "/".equals(path))
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException invalid) {
            return false;
        }
    }
}
