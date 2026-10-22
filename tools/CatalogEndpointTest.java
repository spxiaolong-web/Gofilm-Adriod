package com.spxiaolong.gofilm;

/** Regression checks for the browser transport's public read-only URL boundary. */
public final class CatalogEndpointTest {
    public static void main(String[] args) {
        String[] allowed = {"/api/index", "/api/filmDetail?id=156662",
                "/api/searchFilm?keyword=%E8%AF%9B%E4%BB%99&current=1",
                "/api/filmClassifySearch?Pid=1&current=2"};
        for (String path : allowed) CatalogEndpoint.requirePath(path);
        String[] rejected = {"https://film.spxiaolong.com/api/index", "https://example.com/api/index",
                "//example.com/api/index", "http://example.com/api/index", "javascript:alert(1)",
                "/api/manage/film/list", "/api/cache/del", "/api/index#fragment", "/api/%69ndex",
                "/api/../api/index", "/api/index/", "data:text/html,test"};
        for (String path : rejected) {
            try { CatalogEndpoint.requirePath(path); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Unexpectedly accepted: " + path);
        }
        System.out.println("Catalogue endpoint checks passed: " + (allowed.length + rejected.length));
    }
}
