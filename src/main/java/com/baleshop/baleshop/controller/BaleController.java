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
            @RequestParam("image") MultipartFile[] images,
            @RequestParam(value = "video", required = false) MultipartFile video
    ) throws IOException {
        User actor = sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");

        if ("SELLER".equalsIgnoreCase(actor.getRole())) {
            sellerId = actor.getId();
            sellerName = actor.getName();
        } else if (sellerId == null) {
            sellerId = actor.getId();
            sellerName = actor.getName();
        }

        List<String> imageUrls = cloudinaryService.uploadImages(images);

        if (imageUrls.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "At least one image is required"
            ));
        }

        String videoUrl = null;

        if (video != null && !video.isEmpty()) {
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
