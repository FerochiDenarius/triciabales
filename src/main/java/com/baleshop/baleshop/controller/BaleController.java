package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.service.BaleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/triciabales")
@CrossOrigin(origins = "*")
public class BaleController {

    @Autowired
    private BaleService service;

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

        String uploadDir = System.getProperty("user.dir") + "/uploads/";

        File dir = new File(uploadDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            System.out.println("Upload directory created: " + created);
        }

        // Save files
        String imagePath = uploadDir + image.getOriginalFilename();
        String videoPath = uploadDir + video.getOriginalFilename();

        image.transferTo(new File(imagePath));
        video.transferTo(new File(videoPath));

        // Save to DB
        Bale bale = new Bale();
        bale.setName(name);
        bale.setPrice(price);
        bale.setWeight(weight);
        bale.setCategory(category);
        bale.setDescription(description);
        bale.setStatus(status);

        // IMPORTANT: These paths will be used in frontend
        bale.setImageUrl("/uploads/" + image.getOriginalFilename());
        bale.setVideoUrl("/uploads/" + video.getOriginalFilename());

        return service.addBale(bale);
    }
}