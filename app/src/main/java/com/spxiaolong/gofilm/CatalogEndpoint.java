package com.spxiaolong.gofilm;

import java.net.URI;

/** The background browser may read only these public catalogue endpoints. */
final class CatalogEndpoint {
    static void requirePath(String value) {
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("接口地址不受支持");
            }
            String path = uri.getRawPath();
            if (!"/api/index".equals(path) && !"/api/filmClassifySearch".equals(path)
                    && !"/api/searchFilm".equals(path) && !"/api/filmDetail".equals(path)) {
                throw new IllegalArgumentException("接口地址不受支持");
            }
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("接口地址不受支持", exception);
        }
    }
}
