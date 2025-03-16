package com.microproject;

import java.util.Map;

public class CachedResponse {
    int statusCode;
    Map<String, String> headers;
    byte[] body;

    public CachedResponse(int statusCode, Map<String, String> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }
}
