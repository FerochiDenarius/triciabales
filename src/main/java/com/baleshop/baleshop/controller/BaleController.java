package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.BaleRepository;
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

    // ✅ GET all bales
    @GetMapping
    public List<Bale> getAllBales() {
        return service.getAllBales();
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

        return baleRepository.findBySellerId(sellerId);
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
                        sellerId,
                        sellerName,
                        imageUrls,
                        videoUrl
                )
        );
    }

    private boolean isFashionRelated(String name, String category, String type, String description) {
        String combined = String.join(" ",
                        safeValue(name),
                        safeValue(category),
                        safeValue(type),
                        safeValue(description))
                .toLowerCase();

        List<String> fashionKeywords = List.of(
                "fashion", "bale", "cloth", "clothes", "clothing", "apparel",
                "dress", "shirt", "skirt", "trouser", "trousers", "jeans",
                "hoodie", "jacket", "sneaker", "shoe", "bag", "handbag",
                "boutique", "wear", "outfit", "kids wear", "ladies wear", "mens wear"
        );

        return fashionKeywords.stream().anyMatch(combined::contains);
    }

    private String safeValue(String value) {
        return value == null ? "" : value.trim();
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
