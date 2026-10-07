package com.ai.client.pet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 余额查询：{@code GET https://api.deepseek.com/user/balance}。
 *
 * <p>返回形如 {@code {"is_available":true,"balance_infos":[{"total_balance":"30.00"}]}}，
 * 取第一个 balance_infos 的 total_balance。</p>
 */
public final class BalancePoller {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private BalancePoller() {
    }

    public static CompletableFuture<Double> fetch(String url, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("未填写 API Key"));
        }
        if (url == null || url.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("余额接口地址为空"));
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("余额接口地址不合法：" + url));
        }

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new RuntimeException("余额接口 HTTP " + response.statusCode());
                    }
                    return parse(response.body());
                });
    }

    private static double parse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray infos = json.getAsJsonArray("balance_infos");
            if (infos == null || infos.isEmpty()) {
                throw new RuntimeException("返回中没有 balance_infos");
            }
            return infos.get(0).getAsJsonObject().get("total_balance").getAsDouble();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("余额返回解析失败");
        }
    }
}
