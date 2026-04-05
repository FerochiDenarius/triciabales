package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.service.BaleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.baleshop.baleshop.service.CloudinaryService;


import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/triciabales")
@CrossOrigin(origins = "*")
public class BaleController {

    @Autowired
    private BaleService service;
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
            @RequestParam("image") MultipartFile image,
            @RequestParam("video") MultipartFile video
    ) throws IOException {

        String imageUrl = cloudinaryService.uploadImage(image);
        String videoUrl = cloudinaryService.uploadVideo(video);

// Save to DB
        Bale bale = new Bale();
        bale.setName(name);
        bale.setPrice(price);
        bale.setWeight(weight);
        bale.setCategory(category);
        bale.setDescription(description);
        bale.setStatus(status);

        bale.setImageUrl(imageUrl);
        bale.setVideoUrl(videoUrl);

        return service.addBale(bale);
    }
}