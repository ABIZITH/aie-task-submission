package com.desertstar.integration;

import com.desertstar.integration.http.ApiServer;

public final class Main {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("TRAINING_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("TRAINING_API_KEY environment variable is not set. See .env.example.");
            System.exit(1);
        }
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            port = Integer.parseInt(portEnv);
        }

        ApiServer server = new ApiServer(port, apiKey);
        server.start();
        System.out.println("Integration prototype listening on http://localhost:" + port
                + " (POST /api/v1/invoices, GET /api/v1/documents/{id}/status)");
    }
}
