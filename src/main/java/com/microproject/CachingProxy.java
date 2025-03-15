package com.microproject;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CachingProxy {

    private static final Logger logger = LoggerFactory.getLogger(CachingProxy.class);

    @Parameter(names = "--port", description = "Port to listen on", required = true)
    private int port;

    @Parameter(names = "--origin", description = "Origin server URL", required = true)
    private String origin;

    @Parameter(names = "--clear-cache", description = "Clear the cache")
    private boolean clearCache = false;

    private final Map<String, CachedResponse> cache = new HashMap<>();

    //TODO make pool size a parameter
    // pool size can be 2-3 times the cores available in the system as a caching proxy is primarily I/O bound
    private final ExecutorService executor = Executors.newFixedThreadPool(10); // Thread pool for handling requests

    public static void main(String[] args) throws IOException {
        CachingProxy proxy = new CachingProxy();
        JCommander.newBuilder()
                .addObject(proxy)
                .build()
                .parse(args);

        proxy.start();
    }

    public void start() throws IOException {
        if (clearCache) {
            //TODO dummy implementation
            cache.clear();
            logger.info("Cache cleared.");
            return;
        }

        logger.info("Starting caching proxy on port {} forwarding to {}", port, origin);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            HttpRequest request = readRequest(clientSocket);
            if (request == null) {
                return; // Invalid request, close the connection
            }

            String cacheKey = request.uri; // Simple cache key based on the URI

            if (cache.containsKey(cacheKey)) {
                // Cache hit
                logger.info("Cache hit for {}", cacheKey);
                CachedResponse cachedResponse = cache.get(cacheKey);
                sendResponse(clientSocket, cachedResponse.statusCode, cachedResponse.headers, cachedResponse.body, "HIT");
            } else {
                // Cache miss
                logger.info("Cache miss for {}", cacheKey);
                HttpResponse originResponse = fetchFromOrigin(request.uri);
                if (originResponse != null) {
                    cache.put(cacheKey, new CachedResponse(originResponse.statusCode, originResponse.headers, originResponse.body));
                    sendResponse(clientSocket, originResponse.statusCode, originResponse.headers, originResponse.body, "MISS");
                } else {
                    // Handle error from origin server
                    sendErrorResponse(clientSocket, HttpStatus.SC_BAD_GATEWAY, "Bad Gateway: Could not fetch from origin server.");
                }
            }
        } catch (IOException e) {
            logger.error("Error handling client: {}", e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.warn("Error closing socket: {}", e.getMessage());
            }
        }
    }

    private HttpRequest readRequest(Socket clientSocket) throws IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(clientSocket.getInputStream()));
        String line = reader.readLine();
        if (line == null) {
            return null; // Connection closed prematurely
        }

        // Very basic request parsing - assumes "GET /path HTTP/1.1"
        String[] parts = line.split(" ");
        if (parts.length < 2 || !parts[0].equalsIgnoreCase("GET")) {
            logger.warn("Invalid request line: {}", line);
            return null;
        }
        String uri = parts[1];
        return new HttpRequest(uri);
    }



    private HttpResponse fetchFromOrigin(String uri) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            URI fullUri = new URI(origin).resolve(uri);
            HttpGet httpGet = new HttpGet(fullUri);

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                Header[] headers = response.getAllHeaders();
                HttpEntity entity = response.getEntity();
                byte[] body = (entity != null) ? EntityUtils.toByteArray(entity) : new byte[0];

                Map<String, String> headerMap = new HashMap<>();
                for (Header header : headers) {
                    headerMap.put(header.getName(), header.getValue());
                }

                return new HttpResponse(statusCode, headerMap, body);
            } catch (IOException e) {
                logger.error("Error fetching from origin {}: {}", uri, e.getMessage());
                return null;
            }
        } catch (URISyntaxException e) {
            logger.error("Invalid URI: {}", e.getMessage());
            return null;
        } catch (IOException e) {
            logger.error("Error creating HttpClient: {}", e.getMessage());
            return null;
        }
    }

    private void sendResponse(Socket clientSocket, int statusCode, Map<String, String> headers, byte[] body, String cacheStatus) throws IOException {
        java.io.PrintWriter writer = new java.io.PrintWriter(clientSocket.getOutputStream(), true); // Auto-flush
        writer.printf("HTTP/1.1 %d OK\r\n", statusCode); // Simple OK status
        headers.forEach((name, value) -> writer.printf("%s: %s\r\n", name, value));
        writer.printf("X-Cache: %s\r\n", cacheStatus);
        writer.printf("Content-Length: %d\r\n", body.length);
        writer.printf("\r\n"); // End of headers
        writer.flush();

        clientSocket.getOutputStream().write(body);
        clientSocket.getOutputStream().flush();
    }


    private void sendErrorResponse(Socket clientSocket, int statusCode, String message) throws IOException {
        java.io.PrintWriter writer = new java.io.PrintWriter(clientSocket.getOutputStream(), true);
        writer.printf("HTTP/1.1 %d %s\r\n", statusCode, message);
        writer.printf("Content-Type: text/plain\r\n");
        writer.printf("\r\n");
        writer.printf(message);
        writer.flush();
    }


    static class HttpRequest {
        String uri;

        public HttpRequest(String uri) {
            this.uri = uri;
        }
    }

    static class HttpResponse {
        int statusCode;
        Map<String, String> headers;
        byte[] body;

        public HttpResponse(int statusCode, Map<String, String> headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }
    }

    static class CachedResponse {
        int statusCode;
        Map<String, String> headers;
        byte[] body;

        public CachedResponse(int statusCode, Map<String, String> headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }
    }
}