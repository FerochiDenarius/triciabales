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
}