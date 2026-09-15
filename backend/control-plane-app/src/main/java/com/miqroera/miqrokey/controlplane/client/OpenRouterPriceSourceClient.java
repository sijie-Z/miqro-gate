package com.miqroera.miqrokey.controlplane.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.config.PriceSyncProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenRouter public model index ({@code GET /api/v1/models}, issue #585): the
 * default price source — reachable from the mainland demo host where
 * jsDelivr/GitHub-raw deliveries of community price datasets are not (实测 18
 * KB/s / 超时). Prices are USD per token as decimal strings; variant ids
 * containing {@code ':'} (e.g. {@code :batch}, {@code :free}) are skipped —
 * their economics differ from the standard route.
 */
public class OpenRouterPriceSourceClient implements PriceSourceClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI url;
    private final Duration requestTimeout;
    private final int maxBytes;

    public OpenRouterPriceSourceClient(ObjectMapper objectMapper, PriceSyncProperties properties) {
        this.objectMapper = objectMapper;
        this.url = URI.create(properties.getUrl());
        this.requestTimeout = properties.getRequestTimeout();
        this.maxBytes = properties.getMaxBytes();
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
    }

    @Override
    public List<SourceModelPrice> fetch() {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new PriceSourceException("PRICE_SOURCE_UNREACHABLE", "价格源不可达：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceSourceException("PRICE_SOURCE_UNREACHABLE", "价格源请求被中断");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new PriceSourceException("PRICE_SOURCE_HTTP_" + response.statusCode(),
                        "价格源返回 HTTP " + response.statusCode());
            }
            return parse(readBounded(body));
        } catch (IOException e) {
            throw new PriceSourceException("PRICE_SOURCE_UNREACHABLE", "价格源读取失败：" + e.getClass().getSimpleName());
        }
    }

    private byte[] readBounded(InputStream body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = body.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new PriceSourceException("PRICE_SOURCE_TOO_LARGE", "价格源响应超过大小上限");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private List<SourceModelPrice> parse(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException e) {
            throw new PriceSourceException("PRICE_SOURCE_UNPARSEABLE", "价格源响应不是合法 JSON");
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new PriceSourceException("PRICE_SOURCE_UNPARSEABLE", "价格源响应缺少 data 数组");
        }
        List<SourceModelPrice> models = new ArrayList<>();
        for (JsonNode node : data) {
            String slug = node.path("id").asText(null);
            if (slug == null || slug.isBlank() || slug.contains(":")) {
                continue; // variant routes (:batch/:free/…) have different economics
            }
            JsonNode pricing = node.path("pricing");
            BigDecimal prompt = decimal(pricing, "prompt");
            BigDecimal completion = decimal(pricing, "completion");
            if (prompt == null || completion == null) {
                continue; // unpriced or malformed entry
            }
            models.add(new SourceModelPrice(slug, prompt, completion, decimal(pricing, "input_cache_read"),
                    decimal(pricing, "input_cache_write")));
        }
        return models;
    }

    private static BigDecimal decimal(JsonNode pricing, String field) {
        JsonNode value = pricing.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
