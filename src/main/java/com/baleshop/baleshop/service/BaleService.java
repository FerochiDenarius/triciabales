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
        bale.setCategoryType(categoryType);
        bale.setBrand(brand);
        bale.setColor(color);
        bale.setMaterial(material);
        bale.setCondition(condition);
        bale.setLength(length);
        bale.setModel(model);
        bale.setYear(year);
        bale.setMetadataJson(metadataJson);
        bale.setSellerId(sellerId);
        bale.setSellerName(sellerName);
        bale.setImageUrls(imageUrls);
        bale.setImageUrl(imageUrls == null || imageUrls.isEmpty() ? null : imageUrls.get(0));
        bale.setVideoUrl(videoUrl);

        return repo.save(bale);
    }
}
