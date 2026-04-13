package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.BaleRepository;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.BaleService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.baleshop.baleshop.service.CloudinaryService;
import org.springframework.web.server.ResponseStatusException;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/triciabales")
@CrossOrigin(origins = "*")
public class BaleController {

    @Autowired
    private BaleService service;
    @Autowired
    private BaleRepository baleRepository;
    @Autowired
    private CloudinaryService cloudinaryService;
    @Autowired
    private SessionAuthService sessionAuthService;
    @Autowired
    private UserRepository userRepository;

    // ✅ GET all bales
    @GetMapping
    public List<Bale> getAllBales() {
        return enrichSellerDetails(service.getAllBales());
    }

    @GetMapping("/seller/{sellerId}")
    public List<Bale> getSellerBales(@PathVariable Long sellerId, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);

        if ("SELLER".equalsIgnoreCase(actor.getRole()) && !sellerId.equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own products");
        }

        if ("BUYER".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to view seller products");
        }

        return enrichSellerDetails(baleRepository.findBySellerId(sellerId));
    }

    // ✅ OLD method (keep it if needed)
    @PostMapping
    public Bale addBale(@RequestBody Bale bale) {
        return service.addBale(bale);
    }

    // 🔥 NEW: Upload with image + video
    @PostMapping("/upload")
    public ResponseEntity<?> uploadBale(
            HttpServletRequest request,
            @RequestParam("name") String name,
            @RequestParam("price") double price,
            @RequestParam("weight") String weight,
            @RequestParam("category") String category,
            @RequestParam("description") String description,
            @RequestParam("status") String status,
            @RequestParam(value = "type", defaultValue = "bale") String type,
            @RequestParam(value = "categoryType", required = false) String categoryType,
            @RequestParam(value = "brand", required = false) String brand,
            @RequestParam(value = "color", required = false) String color,
            @RequestParam(value = "material", required = false) String material,
            @RequestParam(value = "condition", required = false) String condition,
            @RequestParam(value = "length", required = false) String length,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "year", required = false) String year,
            @RequestParam(value = "metadataJson", required = false) String metadataJson,
            @RequestParam(value = "sellerId", required = false) Long sellerId,
            @RequestParam(value = "sellerName", required = false) String sellerName,
            @RequestParam(value = "image", required = false) MultipartFile[] images,
            @RequestParam(value = "video", required = false) MultipartFile video
    ) throws IOException {
        return handleUploadBale(
                request,
                name,
                price,
                weight,
                category,
                description,
                status,
                type,
                categoryType,
                brand,
                color,
                material,
                condition,
                length,
                model,
                year,
                metadataJson,
                sellerId,
                sellerName,
                images,
                video,
                false
        );
    }

    @PostMapping("/upload/video-only")
    public ResponseEntity<?> uploadVideoOnlyBale(
            HttpServletRequest request,
            @RequestParam("name") String name,
            @RequestParam("price") double price,
            @RequestParam("weight") String weight,
            @RequestParam("category") String category,
            @RequestParam("description") String description,
            @RequestParam("status") String status,
            @RequestParam(value = "type", defaultValue = "product_video") String type,
            @RequestParam(value = "categoryType", required = false) String categoryType,
            @RequestParam(value = "brand", required = false) String brand,
            @RequestParam(value = "color", required = false) String color,
            @RequestParam(value = "material", required = false) String material,
            @RequestParam(value = "condition", required = false) String condition,
            @RequestParam(value = "length", required = false) String length,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "year", required = false) String year,
            @RequestParam(value = "metadataJson", required = false) String metadataJson,
            @RequestParam(value = "sellerId", required = false) Long sellerId,
            @RequestParam(value = "sellerName", required = false) String sellerName,
            @RequestParam("video") MultipartFile video
    ) throws IOException {
        return handleUploadBale(
                request,
                name,
                price,
                weight,
                category,
                description,
                status,
                type,
                categoryType,
                brand,
                color,
                material,
                condition,
                length,
                model,
                year,
                metadataJson,
                sellerId,
                sellerName,
                null,
                video,
                true
        );
    }

    private ResponseEntity<?> handleUploadBale(
            HttpServletRequest request,
            String name,
            double price,
            String weight,
            String category,
            String description,
            String status,
            String type,
            String categoryType,
            String brand,
            String color,
            String material,
            String condition,
            String length,
            String model,
            String year,
            String metadataJson,
            Long sellerId,
            String sellerName,
            MultipartFile[] images,
            MultipartFile video,
            boolean explicitVideoOnly
    ) throws IOException {
        User actor = sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");

        if ("SELLER".equalsIgnoreCase(actor.getRole())) {
            sellerId = actor.getId();
            sellerName = actor.getName();
        } else if (sellerId == null) {
            sellerId = actor.getId();
            sellerName = actor.getName();
        }

        MultipartFile[] nonEmptyImages = images == null
                ? new MultipartFile[0]
                : Arrays.stream(images)
                .filter(file -> file != null && !file.isEmpty())
                .toArray(MultipartFile[]::new);
        boolean hasImages = nonEmptyImages.length > 0;
        boolean hasVideo = video != null && !video.isEmpty();

        if (!hasImages && !hasVideo) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Upload at least one image or one video"
            ));
        }

        if ((explicitVideoOnly || !hasImages) && !isFashionRelated(name, category, type, description)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Video-only listings must be fashion related"
            ));
        }

        List<String> imageUrls = hasImages ? cloudinaryService.uploadImages(nonEmptyImages) : List.of();
        String videoUrl = null;

        if (hasVideo) {
            videoUrl = cloudinaryService.uploadVideo(video);
        }

        return ResponseEntity.ok(
                service.createBale(
                        name,
                        price,
                        weight,
                        category,
                        description,
                        status,
                        type,
                        stringValue(categoryType, type),
                        safeValue(brand),
                        safeValue(color),
                        safeValue(material),
                        safeValue(condition),
                        safeValue(length),
                        safeValue(model),
                        safeValue(year),
                        safeValue(metadataJson),
                        sellerId,
                        sellerName,
                        imageUrls,
                        videoUrl
                )
        );
    }

    private boolean isFashionRelated(String name, String category, String type, String description) {
        String normalizedType = safeValue(type).toLowerCase();

        if (
                "single".equals(normalizedType) ||
                        "dress".equals(normalizedType) ||
                        "bale".equals(normalizedType) ||
                        "shoe".equals(normalizedType) ||
                        "bag".equals(normalizedType) ||
                        "wig".equals(normalizedType) ||
                        "fabric".equals(normalizedType) ||
                        "accessory".equals(normalizedType)
        ) {
            return true;
        }

        String combined = String.join(" ",
                        safeValue(name),
                        safeValue(category),
                        safeValue(type),
                        safeValue(description))
                .toLowerCase();

        List<String> fashionKeywords = List.of(
                "fashion", "bale", "single", "cloth", "clothes", "clothing", "apparel",
                "dress", "shirt", "top", "blouse", "gown", "skirt", "trouser", "trousers",
                "jeans", "hoodie", "jacket", "sneaker", "shoe", "bag", "handbag",
                "boutique", "wear", "outfit", "ladies", "women", "women's", "mens", "men",
                "kids", "kids wear", "ladies wear", "mens wear", "wig", "hair", "human hair",
                "fabric", "textile", "material", "accessory", "accessories"
        );

        return fashionKeywords.stream().anyMatch(combined::contains);
    }

    private List<Bale> enrichSellerDetails(List<Bale> bales) {
        bales.forEach(this::enrichSellerDetails);
        return bales;
    }

    private Bale enrichSellerDetails(Bale bale) {
        if (bale == null || bale.getSellerId() == null) {
            return bale;
        }

        userRepository.findById(bale.getSellerId()).ifPresent(seller -> {
            bale.setSellerName(seller.getName());
            bale.setSellerPhone(seller.getPhone());
            bale.setSellerProfileImageUrl(seller.getProfileImageUrl());
        });

        return bale;
    }

    private String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        String text = value.toString().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product price");
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> toggleStatus(@PathVariable int id, HttpServletRequest request) {
        User actor = sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");
        Optional<Bale> optionalBale = baleRepository.findById(id);

        if (optionalBale.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Bale bale = optionalBale.get();

        if ("SELLER".equalsIgnoreCase(actor.getRole()) && !actor.getId().equals(bale.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own products");
        }

        if ("sold".equalsIgnoreCase(bale.getStatus())) {
            bale.setStatus("available");
        } else {
            bale.setStatus("sold");
        }

        baleRepository.save(bale);

        return ResponseEntity.ok(bale);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateBale(
            @PathVariable int id,
            @RequestBody Map<String, Object> updates,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");
        Bale bale = baleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bale not found"));

        if ("SELLER".equalsIgnoreCase(actor.getRole()) && !actor.getId().equals(bale.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own products");
        }

        if (updates.containsKey("name")) {
            bale.setName(stringValue(updates.get("name"), bale.getName()));
        }

        if (updates.containsKey("price")) {
            bale.setPrice(doubleValue(updates.get("price"), bale.getPrice()));
        }

        if (updates.containsKey("weight")) {
            bale.setWeight(stringValue(updates.get("weight"), bale.getWeight()));
        }

        if (updates.containsKey("category")) {
            bale.setCategory(stringValue(updates.get("category"), bale.getCategory()));
        }

        if (updates.containsKey("description")) {
            bale.setDescription(stringValue(updates.get("description"), bale.getDescription()));
        }

        if (updates.containsKey("status")) {
            String status = stringValue(updates.get("status"), bale.getStatus());
            if (!"available".equalsIgnoreCase(status) && !"preorder".equalsIgnoreCase(status) && !"sold".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product status");
            }
            bale.setStatus(status.toLowerCase());
        }

        if (updates.containsKey("type")) {
            bale.setType(stringValue(updates.get("type"), bale.getType()));
        }

        if (updates.containsKey("categoryType")) {
            bale.setCategoryType(stringValue(updates.get("categoryType"), bale.getCategoryType()));
        }

        if (updates.containsKey("brand")) {
            bale.setBrand(stringValue(updates.get("brand"), bale.getBrand()));
        }

        if (updates.containsKey("color")) {
            bale.setColor(stringValue(updates.get("color"), bale.getColor()));
        }

        if (updates.containsKey("material")) {
            bale.setMaterial(stringValue(updates.get("material"), bale.getMaterial()));
        }

        if (updates.containsKey("condition")) {
            bale.setCondition(stringValue(updates.get("condition"), bale.getCondition()));
        }

        if (updates.containsKey("length")) {
            bale.setLength(stringValue(updates.get("length"), bale.getLength()));
        }

        if (updates.containsKey("model")) {
            bale.setModel(stringValue(updates.get("model"), bale.getModel()));
        }

        if (updates.containsKey("year")) {
            bale.setYear(stringValue(updates.get("year"), bale.getYear()));
        }

        if (updates.containsKey("metadataJson")) {
            bale.setMetadataJson(stringValue(updates.get("metadataJson"), bale.getMetadataJson()));
        }

        return ResponseEntity.ok(baleRepository.save(bale));
    }

    @PutMapping("/{id}/media")
    public ResponseEntity<?> updateBaleMedia(
            @PathVariable int id,
            HttpServletRequest request,
            @RequestParam(value = "retainedImageUrls", required = false) List<String> retainedImageUrls,
            @RequestParam(value = "image", required = false) MultipartFile[] images
    ) throws IOException {
        User actor = sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");
        Bale bale = baleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bale not found"));

        if ("SELLER".equalsIgnoreCase(actor.getRole()) && !actor.getId().equals(bale.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own products");
        }

        List<String> nextImageUrls = new ArrayList<>();

        if (retainedImageUrls != null) {
            retainedImageUrls.stream()
                    .map(this::safeValue)
                    .filter(url -> !url.isBlank())
                    .forEach(nextImageUrls::add);
        }

        MultipartFile[] nonEmptyImages = images == null
                ? new MultipartFile[0]
                : Arrays.stream(images)
                .filter(file -> file != null && !file.isEmpty())
                .toArray(MultipartFile[]::new);

        nextImageUrls.addAll(cloudinaryService.uploadImages(nonEmptyImages));

        if (nextImageUrls.isEmpty() && (bale.getVideoUrl() == null || bale.getVideoUrl().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product must keep at least one image or video");
        }

        bale.setImageUrls(nextImageUrls);
        bale.setImageUrl(nextImageUrls.isEmpty() ? null : nextImageUrls.get(0));

        return ResponseEntity.ok(baleRepository.save(bale));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBale(@PathVariable int id, HttpServletRequest request) {
        User actor = sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");
        if (!baleRepository.existsById(id)) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "Bale not found"
            ));
        }

        Bale bale = baleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bale not found"));

        if ("SELLER".equalsIgnoreCase(actor.getRole()) && !actor.getId().equals(bale.getSellerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own products");
        }

        baleRepository.deleteById(id);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Bale deleted successfully"
        ));
    }
}
