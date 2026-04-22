package com.baleshop.baleshop.service;

import com.baleshop.baleshop.dto.CartItemDto;
import com.baleshop.baleshop.dto.DeliveryEstimateRequest;
import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.BaleRepository;
import com.baleshop.baleshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DeliveryEstimateService {

    private static final String GOOGLE_DISTANCE_MATRIX_URL = "https://maps.googleapis.com/maps/api/distancematrix/json";
    private static final String GOOGLE_PLACES_AUTOCOMPLETE_URL = "https://maps.googleapis.com/maps/api/place/autocomplete/json";

    private final BaleRepository baleRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.google.maps.api-key:}")
    private String googleMapsApiKey;

    @Value("${app.delivery.default-origin-address:Oyarifa, Accra, Ghana}")
    private String defaultOriginAddress;

    public DeliveryEstimateService(
            BaleRepository baleRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.baleRepository = baleRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public Map<String, Object> estimate(DeliveryEstimateRequest request) {
        requireConfigured();

        String buyerAddress = buildBuyerAddress(request);
        String buyerDestination = buildBuyerDestination(request, buyerAddress);
        if (buyerAddress == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Delivery address is required");
        }

        Set<Long> sellerIds = sellerIds(request);
        if (sellerIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart items are required for delivery estimate");
        }

        List<Map<String, Object>> sellerEstimates = new ArrayList<>();
        double totalDistanceKm = 0.0;
        double totalDeliveryFee = 0.0;

        for (Long sellerId : sellerIds) {
            User seller = userRepository.findById(sellerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller not found for delivery estimate"));
            String shopAddress = firstNonBlank(seller.getShopAddress(), seller.getAddress(), defaultOriginAddress);
            if (shopAddress == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller " + sellerId + " has no shop address for delivery estimate");
            }

            DistanceResult distance;
            double fee;
            if (isOutsideAccra(shopAddress)) {
                distance = new DistanceResult(0.0, "Parcel service");
                fee = 100.0;
            } else {
                distance = distance(shopAddress, buyerDestination);
                fee = calculateDeliveryFee(distance.distanceKm());
            }
            totalDistanceKm += distance.distanceKm();
            totalDeliveryFee += fee;

            Map<String, Object> sellerEstimate = new LinkedHashMap<>();
            sellerEstimate.put("sellerId", sellerId);
            sellerEstimate.put("sellerName", seller.getName());
            sellerEstimate.put("shopAddress", shopAddress);
            sellerEstimate.put("distanceKm", round(distance.distanceKm()));
            sellerEstimate.put("durationText", distance.durationText());
            sellerEstimate.put("deliveryFee", fee);
            sellerEstimate.put("outsideAccra", isOutsideAccra(shopAddress));
            sellerEstimates.add(sellerEstimate);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buyerAddress", buyerAddress);
        result.put("sellerCount", sellerIds.size());
        result.put("distanceKm", round(totalDistanceKm));
        result.put("deliveryFee", round(totalDeliveryFee));
        result.put("currency", "GHS");
        result.put("pricing", "0-5km=40, 5-10km=60, 10-15km=80, 15-20km=100, over 20km=100+5/km");
        result.put("outsideAccraPricing", "Seller shops outside Accra use fixed parcel delivery fee of GHS 100");
        result.put("sellerEstimates", sellerEstimates);
        return result;
    }

    public Map<String, Object> placePredictions(String input) {
        requireConfigured();

        String cleanInput = normalizeAddress(input);
        if (cleanInput == null || cleanInput.length() < 3) {
            return Map.of("success", true, "predictions", List.of());
        }

        String url = GOOGLE_PLACES_AUTOCOMPLETE_URL
                + "?input=" + encode(cleanInput)
                + "&components=country:gh"
                + "&types=geocode"
                + "&key=" + encode(googleMapsApiKey);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());

            if (response.statusCode() >= 400 || !"OK".equalsIgnoreCase(body.path("status").asText(""))
                    && !"ZERO_RESULTS".equalsIgnoreCase(body.path("status").asText(""))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.path("error_message").asText("Could not load address suggestions"));
            }

            List<Map<String, Object>> predictions = new ArrayList<>();
            for (JsonNode prediction : body.path("predictions")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("placeId", prediction.path("place_id").asText(""));
                item.put("description", prediction.path("description").asText(""));
                item.put("mainText", prediction.path("structured_formatting").path("main_text").asText(""));
                item.put("secondaryText", prediction.path("structured_formatting").path("secondary_text").asText(""));
                if (!String.valueOf(item.get("placeId")).isBlank() && !String.valueOf(item.get("description")).isBlank()) {
                    predictions.add(item);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("predictions", predictions);
            return result;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Google Places");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google Places request was interrupted");
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid Google Places response");
        }
    }

    private DistanceResult distance(String origin, String destination) {
        String url = GOOGLE_DISTANCE_MATRIX_URL
                + "?origins=" + encode(origin)
                + "&destinations=" + encode(destination)
                + "&mode=driving"
                + "&units=metric"
                + "&key=" + encode(googleMapsApiKey);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());

            if (response.statusCode() >= 400 || !"OK".equalsIgnoreCase(body.path("status").asText(""))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, body.path("error_message").asText("Could not estimate delivery distance"));
            }

            JsonNode element = body.path("rows").path(0).path("elements").path(0);
            String elementStatus = element.path("status").asText("");
            if (!"OK".equalsIgnoreCase(elementStatus)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google could not calculate delivery distance");
            }

            double meters = element.path("distance").path("value").asDouble(0);
            String durationText = element.path("duration").path("text").asText("");
            return new DistanceResult(meters / 1000.0, durationText);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Google Maps");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google Maps request was interrupted");
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid Google Maps response");
        }
    }

    public double calculateDeliveryFee(double km) {
        if (km <= 5) return 40;
        if (km <= 10) return 60;
        if (km <= 15) return 80;
        if (km <= 20) return 100;
        return 100 + (Math.ceil(km - 20) * 5);
    }

    private Set<Long> sellerIds(DeliveryEstimateRequest request) {
        Set<Long> sellerIds = new LinkedHashSet<>();
        if (request.getItems() == null) {
            return sellerIds;
        }

        for (CartItemDto item : request.getItems()) {
            if (item.getBaleId() == null) {
                continue;
            }
            Bale bale = baleRepository.findById(item.getBaleId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found for delivery estimate"));
            if (bale.getSellerId() != null) {
                sellerIds.add(bale.getSellerId());
            }
        }
        return sellerIds;
    }

    private String buildBuyerAddress(DeliveryEstimateRequest request) {
        StringBuilder builder = new StringBuilder();
        append(builder, request.getAddress());
        append(builder, request.getLandmark());
        append(builder, request.getArea());
        append(builder, request.getRegion());
        append(builder, "Ghana");
        return builder.isEmpty() ? null : builder.toString();
    }

    private String buildBuyerDestination(DeliveryEstimateRequest request, String buyerAddress) {
        String placeId = normalizeAddress(request.getPlaceId());
        if (placeId != null) {
            return "place_id:" + placeId;
        }
        return buyerAddress;
    }

    private void append(StringBuilder builder, String value) {
        String clean = normalizeAddress(value);
        if (clean == null) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(", ");
        }
        builder.append(clean);
    }

    private String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = normalizeAddress(value);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private boolean isOutsideAccra(String shopAddress) {
        String normalized = shopAddress == null ? "" : shopAddress.toLowerCase(Locale.ROOT);
        return !normalized.contains("accra") && !normalized.contains("tema");
    }

    private void requireConfigured() {
        if (googleMapsApiKey == null || googleMapsApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Maps is not configured");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private record DistanceResult(double distanceKm, String durationText) {
    }
}
