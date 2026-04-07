package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.repository.BaleRepository;
import com.baleshop.baleshop.service.BaleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.baleshop.baleshop.service.CloudinaryService;


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

    // ✅ GET all bales
    @GetMapping
    public List<Bale> getAllBales() {
        return service.getAllBales();
    }

    // ✅ OLD method (keep it if needed)
    @PostMapping
    public Bale addBale(@RequestBody Bale bale) {
        return service.addBale(bale);
    }

    // 🔥 NEW: Upload with image + video
    @PostMapping("/upload")
    public Bale uploadBale(
            @RequestParam("name") String name,
            @RequestParam("price") double price,
            @RequestParam("weight") String weight,
            @RequestParam("category") String category,
            @RequestParam("description") String description,
            @RequestParam("status") String status,
            @RequestParam(value = "type", defaultValue = "bale") String type,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "video", required = false) MultipartFile video
    ) throws IOException {

        String imageUrl = cloudinaryService.uploadImage(image);
        String videoUrl = null;

        if (video != null && !video.isEmpty()) {
            videoUrl = cloudinaryService.uploadVideo(video);
        }

// Save to DB
        Bale bale = new Bale();
        bale.setName(name);
        bale.setPrice(price);
        bale.setWeight(weight);
        bale.setCategory(category);
        bale.setDescription(description);
        bale.setStatus(status);
        bale.setType(type);

        bale.setImageUrl(imageUrl);
        bale.setVideoUrl(videoUrl);

        return service.addBale(bale);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> toggleStatus(@PathVariable int id) {
        Optional<Bale> optionalBale = baleRepository.findById(id);

        if (optionalBale.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Bale bale = optionalBale.get();

        if ("sold".equalsIgnoreCase(bale.getStatus())) {
            bale.setStatus("available");
        } else {
            bale.setStatus("sold");
        }

        baleRepository.save(bale);

        return ResponseEntity.ok(bale);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBale(@PathVariable int id) {
        if (!baleRepository.existsById(id)) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "Bale not found"
            ));
        }

        baleRepository.deleteById(id);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Bale deleted successfully"
        ));
    }
}
