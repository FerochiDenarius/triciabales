package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.repository.BaleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BaleService {

    @Autowired
    private BaleRepository repo;

    public List<Bale> getAllBales() {
        return repo.findAll();
    }

    public Bale addBale(Bale bale) {
        return repo.save(bale);
    }

    public Bale createBale(
            String name,
            double price,
            String weight,
            String category,
            String description,
            String status,
            String type,
            List<String> imageUrls,
            String videoUrl
    ) {
        Bale bale = new Bale();
        bale.setName(name);
        bale.setPrice(price);
        bale.setWeight(weight);
        bale.setCategory(category);
        bale.setDescription(description);
        bale.setStatus(status);
        bale.setType(type);
        bale.setImageUrls(imageUrls);
        bale.setImageUrl(imageUrls == null || imageUrls.isEmpty() ? null : imageUrls.get(0));
        bale.setVideoUrl(videoUrl);

        return repo.save(bale);
    }
}
